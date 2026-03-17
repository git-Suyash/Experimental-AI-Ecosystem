package io.vectorcache;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;
import java.util.function.Predicate;
// import java.util.stream.*;

/**
 * A thread-safe, high-performance in-memory vector cache.
 *
 * <p>Features:
 * <ul>
 *   <li>Concurrent reads via striped {@link ReadWriteLock}s (16 stripes)</li>
 *   <li>Parallel cosine-similarity search via a dedicated {@link ForkJoinPool}</li>
 *   <li>LRU eviction when the entry count reaches {@code Config.capacity}</li>
 *   <li>Hard memory ceiling via {@code Config.maxMemoryBytes} (default 256 MB)</li>
 *   <li>Per-entry TTL: entries expire after {@code Config.ttlMs} milliseconds</li>
 *   <li>Background reaper thread that purges expired entries on a fixed schedule</li>
 *   <li>Manual flush: full, partial (predicate), namespace, and tag-based</li>
 *   <li>Session lifecycle helpers: {@code onSessionStart()} / {@code onDataChanged()}</li>
 * </ul>
 *
 * <p>Typical usage:
 * <pre>{@code
 *   var cache = new VectorCache<>(VectorCache.Config.builder()
 *       .capacity(5_000)
 *       .maxMemoryMb(128)
 *       .ttlMs(TimeUnit.MINUTES.toMillis(30))
 *       .build());
 *
 *   cache.put("doc:42", embedding, meta);
 *   cache.onSessionStart("user-99");          // resets session scope
 *   cache.onDataChanged("products");          // flushes namespace
 *   cache.shutdown();
 * }</pre>
 */
public class VectorCache<M> {

    // ═══════════════════════════════════════════════════════════════
    // Constants
    // ═══════════════════════════════════════════════════════════════

    /** Default max entries when none is specified. */
    public static final int    DEFAULT_CAPACITY             = 10_000;

    /** Default memory ceiling: 256 MB. */
    public static final long   DEFAULT_MAX_MEMORY_BYTES     = 256L * 1024 * 1024;

    /** Default TTL: 0 = entries never expire. */
    public static final long   DEFAULT_TTL_MS               = 0L;

    /** Default background reaper interval in milliseconds. */
    public static final long   DEFAULT_REAPER_INTERVAL_MS   = 60_000L;

    /** Estimated JVM overhead per Entry object (header + reference fields). */
    private static final int   ENTRY_OBJECT_OVERHEAD        = 128;

    /** Number of lock stripes for concurrent read/write isolation. */
    private static final int   STRIPE_COUNT                 = 16;

    // ═══════════════════════════════════════════════════════════════
    // Entry
    // ═══════════════════════════════════════════════════════════════

    /** An immutable cached record: embedding + pre-computed norm + metadata + TTL. */
    public static final class Entry<M> {
        final String  id;
        final float[] embedding;
        final int     dimension;
        final double  norm;
        final M       metadata;
        /** Logical namespace tag (e.g. "products", "user:42"). May be null. */
        final String  namespace;
        final long    insertedAtMs;
        /** Absolute expiry epoch in ms; 0 = never expires. */
        final long    expiresAtMs;
        volatile long lastAccessedMs;
        /** Estimated heap cost of this entry in bytes. */
        final long    estimatedBytes;

        Entry(String id, float[] embedding, M metadata, String namespace, long ttlMs) {
            this.id             = Objects.requireNonNull(id, "id");
            this.embedding      = Arrays.copyOf(embedding, embedding.length);
            this.dimension      = embedding.length;
            this.norm           = computeNorm(embedding);
            this.metadata       = metadata;
            this.namespace      = namespace;
            this.insertedAtMs   = System.currentTimeMillis();
            this.lastAccessedMs = this.insertedAtMs;
            this.expiresAtMs    = (ttlMs > 0) ? (this.insertedAtMs + ttlMs) : 0L;
            this.estimatedBytes = (long) embedding.length * 4 + ENTRY_OBJECT_OVERHEAD;
        }

        public String  getId()             { return id; }
        public float[] getEmbedding()      { return Arrays.copyOf(embedding, dimension); }
        public int     getDimension()      { return dimension; }
        public double  getNorm()           { return norm; }
        public M       getMetadata()       { return metadata; }
        public String  getNamespace()      { return namespace; }
        public long    getInsertedAtMs()   { return insertedAtMs; }
        public long    getLastAccessedMs() { return lastAccessedMs; }
        public long    getExpiresAtMs()    { return expiresAtMs; }

        /** Returns {@code true} if this entry has a TTL and it has passed. */
        public boolean isExpired() {
            return expiresAtMs > 0 && System.currentTimeMillis() > expiresAtMs;
        }

        /** Remaining TTL in milliseconds; {@code -1} if no TTL; {@code 0} if already expired. */
        public long remainingTtlMs() {
            if (expiresAtMs == 0) return -1L;
            return Math.max(0L, expiresAtMs - System.currentTimeMillis());
        }

        @Override
        public String toString() {
            return String.format(
                    "Entry{id='%s', dim=%d, norm=%.4f, ns='%s', ttlRemain=%dms, meta=%s}",
                    id, dimension, norm, namespace, remainingTtlMs(), metadata);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SimilarityResult
    // ═══════════════════════════════════════════════════════════════

    public record SimilarityResult<M>(Entry<M> entry, double score)
            implements Comparable<SimilarityResult<M>> {

        @Override
        public int compareTo(SimilarityResult<M> other) {
            return Double.compare(this.score, other.score);
        }

        @Override
        public String toString() {
            return String.format("SimilarityResult{id='%s', score=%.6f}",
                    entry.getId(), score);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // FlushResult
    // ═══════════════════════════════════════════════════════════════

    /** Summary returned by every flush/invalidation operation. */
    public record FlushResult(int removedCount, long freedBytes, String reason) {
        @Override
        public String toString() {
            return String.format("FlushResult{removed=%d, freed=%s, reason='%s'}",
                    removedCount, humanBytes(freedBytes), reason);
        }

        private static String humanBytes(long b) {
            if (b < 1024)        return b + " B";
            if (b < 1_048_576)   return String.format("%.1f KB", b / 1024.0);
            return String.format("%.2f MB", b / 1_048_576.0);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Config
    // ═══════════════════════════════════════════════════════════════

    /** Immutable cache configuration. Build via {@link Config#builder()}. */
    public static final class Config {

        /** Maximum number of entries before LRU eviction kicks in. */
        public final int  capacity;

        /**
         * Hard memory ceiling in bytes.
         * When {@code usedMemoryBytes} exceeds this, the LRU entry is evicted before
         * any new write, regardless of whether {@code capacity} is reached.
         * Default: 256 MB. Set to {@code 0} to disable (not recommended for production).
         */
        public final long maxMemoryBytes;

        /**
         * Per-entry time-to-live in milliseconds.
         * After this duration from insert time, the entry is considered expired and
         * will not be returned by {@code get()} or search operations.
         * Default: {@code 0} (entries never expire).
         */
        public final long ttlMs;

        /**
         * How often the background reaper thread scans for expired entries.
         * Only relevant when {@code ttlMs > 0}.  Default: 60 000 ms.
         */
        public final long reaperIntervalMs;

        /** Number of threads for parallel similarity search. */
        public final int  parallelism;

        private Config(Builder b) {
            if (b.capacity <= 0)         throw new IllegalArgumentException("capacity must be > 0");
            if (b.parallelism <= 0)      throw new IllegalArgumentException("parallelism must be > 0");
            if (b.reaperIntervalMs <= 0) throw new IllegalArgumentException("reaperIntervalMs must be > 0");
            this.capacity         = b.capacity;
            this.maxMemoryBytes   = b.maxMemoryBytes;
            this.ttlMs            = b.ttlMs;
            this.reaperIntervalMs = b.reaperIntervalMs;
            this.parallelism      = b.parallelism;
        }

        public static Builder builder() { return new Builder(); }

        @Override
        public String toString() {
            return String.format(
                    "Config{capacity=%d, maxMemory=%s, ttl=%s, reaperInterval=%dms, parallelism=%d}",
                    capacity,
                    maxMemoryBytes == 0 ? "unlimited"
                            : String.format("%.0f MB", maxMemoryBytes / 1_048_576.0),
                    ttlMs == 0 ? "none" : ttlMs + "ms",
                    reaperIntervalMs, parallelism);
        }

        public static final class Builder {
            private int  capacity         = DEFAULT_CAPACITY;
            private long maxMemoryBytes   = DEFAULT_MAX_MEMORY_BYTES;
            private long ttlMs            = DEFAULT_TTL_MS;
            private long reaperIntervalMs = DEFAULT_REAPER_INTERVAL_MS;
            private int  parallelism      = Runtime.getRuntime().availableProcessors();

            /** Max number of entries (LRU eviction). */
            public Builder capacity(int v)          { this.capacity = v;                    return this; }

            /**
             * Hard memory ceiling in megabytes. {@code 0} to disable.
             * Convenience wrapper for {@link #maxMemoryBytes(long)}.
             */
            public Builder maxMemoryMb(int mb)      { this.maxMemoryBytes = mb * 1_048_576L; return this; }

            /**
             * Hard memory ceiling in bytes. {@code 0} to disable.
             * Defaults to {@value VectorCache#DEFAULT_MAX_MEMORY_BYTES}.
             */
            public Builder maxMemoryBytes(long v)   { this.maxMemoryBytes = v;              return this; }

            /**
             * Per-entry TTL in milliseconds. {@code 0} = no expiry (default).
             * Example: {@code ttlMs(TimeUnit.MINUTES.toMillis(30))}
             */
            public Builder ttlMs(long v)            { this.ttlMs = v;                       return this; }

            /** Background reaper interval in ms. Only used when TTL &gt; 0. */
            public Builder reaperIntervalMs(long v) { this.reaperIntervalMs = v;            return this; }

            public Builder parallelism(int v)       { this.parallelism = v;                 return this; }

            public Config build()                   { return new Config(this); }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Fields
    // ═══════════════════════════════════════════════════════════════

    private final Config                               config;
    private final ConcurrentHashMap<String, Entry<M>>  store;
    private final ReadWriteLock[]                      stripes;
    private final ForkJoinPool                         searchPool;
    private final ReentrantLock                        evictionLock    = new ReentrantLock();
    private final AtomicLong                           usedMemoryBytes = new AtomicLong();
    private final ScheduledExecutorService             reaper;

    // Stats
    private final LongAdder hitCount       = new LongAdder();
    private final LongAdder missCount      = new LongAdder();
    private final LongAdder evictions      = new LongAdder();
    private final LongAdder expiryRemovals = new LongAdder();

    // ═══════════════════════════════════════════════════════════════
    // Construction
    // ═══════════════════════════════════════════════════════════════

    /** Constructs a cache with all defaults (10 000 entries, 256 MB cap, no TTL). */
    public VectorCache() {
        this(Config.builder().build());
    }

    public VectorCache(Config config) {
        this.config     = Objects.requireNonNull(config, "config");
        this.store      = new ConcurrentHashMap<>(Math.min(config.capacity, 4096));
        this.stripes    = new ReadWriteLock[STRIPE_COUNT];
        for (int i = 0; i < STRIPE_COUNT; i++) stripes[i] = new ReentrantReadWriteLock();
        this.searchPool = new ForkJoinPool(config.parallelism);

        // Start background reaper only when TTL is configured
        if (config.ttlMs > 0) {
            this.reaper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "VectorCache-Reaper");
                t.setDaemon(true);   // won't prevent JVM shutdown
                return t;
            });
            reaper.scheduleAtFixedRate(
                    this::reapExpiredEntries,
                    config.reaperIntervalMs,
                    config.reaperIntervalMs,
                    TimeUnit.MILLISECONDS);
        } else {
            this.reaper = null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Core CRUD
    // ═══════════════════════════════════════════════════════════════

    /**
     * Inserts or replaces an embedding (no namespace tag).
     *
     * @param id        unique key
     * @param embedding float vector — copied defensively, caller may reuse the array
     * @param metadata  arbitrary metadata; may be {@code null}
     * @return the previous entry that was displaced, or empty
     */
    public Optional<Entry<M>> put(String id, float[] embedding, M metadata) {
        return put(id, embedding, metadata, null);
    }

    /**
     * Inserts or replaces an embedding with an explicit namespace tag.
     *
     * <p>Namespaces let you flush a logical group at once via {@link #flushNamespace(String)}.
     * Example: {@code put("doc:99", vec, meta, "products")}
     *
     * @param id        unique key
     * @param embedding float vector — copied defensively
     * @param metadata  arbitrary metadata; may be {@code null}
     * @param namespace logical group label; may be {@code null}
     * @return the previous entry that was displaced, or empty
     */
    public Optional<Entry<M>> put(String id, float[] embedding, M metadata, String namespace) {
        validateEmbedding(embedding);
        Entry<M> newEntry = new Entry<>(id, embedding, metadata, namespace, config.ttlMs);

        Lock writeLock = stripe(id).writeLock();
        writeLock.lock();
        try {
            evictIfNeeded(id, newEntry.estimatedBytes);
            Entry<M> old = store.put(id, newEntry);
            long delta = newEntry.estimatedBytes - (old != null ? old.estimatedBytes : 0L);
            usedMemoryBytes.addAndGet(delta);
            return Optional.ofNullable(old);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Retrieves an entry by id.
     *
     * <p>Returns empty if the entry does not exist or has expired.
     * Updates {@code lastAccessedMs} for LRU purposes on a cache hit.
     */
    public Optional<Entry<M>> get(String id) {
        Lock readLock = stripe(id).readLock();
        readLock.lock();
        try {
            Entry<M> entry = store.get(id);
            if (entry == null) {
                missCount.increment();
                return Optional.empty();
            }
            if (entry.isExpired()) {
                // Lazy expiry — reaper will clean up; report as a miss
                missCount.increment();
                return Optional.empty();
            }
            entry.lastAccessedMs = System.currentTimeMillis();
            hitCount.increment();
            return Optional.of(entry);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Removes a single entry by id.
     *
     * @return the removed entry, or empty if not present
     */
    public Optional<Entry<M>> remove(String id) {
        Lock writeLock = stripe(id).writeLock();
        writeLock.lock();
        try {
            Entry<M> removed = store.remove(id);
            if (removed != null) usedMemoryBytes.addAndGet(-removed.estimatedBytes);
            return Optional.ofNullable(removed);
        } finally {
            writeLock.unlock();
        }
    }

    /** Returns {@code true} if the key exists and has not expired. */
    public boolean contains(String id) {
        Entry<M> e = store.get(id);
        return e != null && !e.isExpired();
    }

    /** Returns the number of stored entries (may include not-yet-reaped expired ones). */
    public int size() { return store.size(); }

    // ═══════════════════════════════════════════════════════════════
    // Flush / Invalidation API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Removes <em>all</em> entries from the cache and resets memory accounting.
     *
     * <p>Use when the entire embedding corpus is re-indexed or before a major
     * re-configuration. Prefer {@link #flushNamespace(String)} for targeted invalidation.
     *
     * @return summary of what was removed
     */
    public FlushResult flushAll() {
        return flushWhere(e -> true, "flushAll");
    }

    /**
     * Removes all entries whose namespace tag equals {@code namespace}.
     *
     * <p>Ideal after re-indexing a specific data partition — e.g., after updating
     * the product catalogue, call {@code onDataChanged("products")} which delegates here.
     *
     * @param namespace the namespace string supplied at insert time; must not be {@code null}
     * @return summary of what was removed
     */
    public FlushResult flushNamespace(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        return flushWhere(e -> namespace.equals(e.namespace),
                "flushNamespace(" + namespace + ")");
    }

    /**
     * Removes all entries that satisfy the given predicate.
     *
     * <p>Flexible surgical flush — target any combination of metadata, namespace, or age:
     * <pre>{@code
     * // Remove entries inserted more than 10 minutes ago
     * cache.flushWhere(
     *     e -> System.currentTimeMillis() - e.getInsertedAtMs() > 600_000,
     *     "stale-purge");
     *
     * // Remove a specific user's embeddings
     * cache.flushWhere(
     *     e -> "user:42".equals(e.getNamespace()),
     *     "user-42-logout");
     * }</pre>
     *
     * @param predicate function returning {@code true} for entries to remove; must not be {@code null}
     * @param reason    human-readable label included in the returned {@link FlushResult}
     * @return summary of what was removed
     */
    public FlushResult flushWhere(Predicate<Entry<M>> predicate, String reason) {
        Objects.requireNonNull(predicate, "predicate");
        int  removed    = 0;
        long freedBytes = 0L;

        for (Entry<M> entry : store.values()) {
            if (!predicate.test(entry)) continue;

            Lock writeLock = stripe(entry.id).writeLock();
            writeLock.lock();
            try {
                // Re-fetch under write lock — another thread may have removed or replaced it
                Entry<M> current = store.get(entry.id);
                if (current != null && predicate.test(current)) {
                    store.remove(entry.id);
                    usedMemoryBytes.addAndGet(-current.estimatedBytes);
                    freedBytes += current.estimatedBytes;
                    removed++;
                }
            } finally {
                writeLock.unlock();
            }
        }
        return new FlushResult(removed, freedBytes, reason);
    }

    /**
     * Removes all entries that have already passed their TTL.
     *
     * <p>This is called automatically by the background reaper when TTL is configured.
     * You may call it manually for immediate cleanup before a search or memory check.
     *
     * @return summary of what was removed
     */
    public FlushResult flushExpired() {
        FlushResult r = flushWhere(Entry::isExpired, "flushExpired");
        expiryRemovals.add(r.removedCount());
        return r;
    }

    // ═══════════════════════════════════════════════════════════════
    // Session lifecycle API
    // ═══════════════════════════════════════════════════════════════

    /**
     * Call this when a new user session or request context begins.
     *
     * <p>This method:
     * <ol>
     *   <li>Flushes all <em>expired</em> entries immediately (lazy reap ahead of schedule)</li>
     *   <li>Resets the hit/miss counters so the new session gets clean metrics</li>
     * </ol>
     *
     * <p>It intentionally does <strong>not</strong> wipe valid, non-expired entries —
     * embeddings loaded in a previous session remain useful across sessions.
     * If you need a clean slate per session (e.g. strict data isolation), call
     * {@link #flushAll()} instead.
     *
     * @param sessionId opaque identifier used for logging; may be any string
     * @return summary of the expired entries that were cleared at session start
     */
    public FlushResult onSessionStart(String sessionId) {
        FlushResult expiredFlush = flushExpired();

        // Reset per-session stats window
        hitCount.reset();
        missCount.reset();

        return new FlushResult(
                expiredFlush.removedCount(),
                expiredFlush.freedBytes(),
                "onSessionStart(sessionId=" + sessionId + ")");
    }

    /**
     * Call this when the underlying data corpus has changed and cached embeddings
     * for a particular namespace may be stale.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>If {@code namespace} is non-null → only that namespace is invalidated
     *       (delegates to {@link #flushNamespace(String)})</li>
     *   <li>If {@code namespace} is {@code null} → all entries are invalidated
     *       (delegates to {@link #flushAll()})</li>
     * </ul>
     *
     * <p>Typical call sites:
     * <ul>
     *   <li>Product catalogue re-index: {@code onDataChanged("products")}</li>
     *   <li>User profile change: {@code onDataChanged("user:42")}</li>
     *   <li>Full corpus re-index: {@code onDataChanged(null)}</li>
     * </ul>
     *
     * @param namespace namespace to invalidate, or {@code null} for a full flush
     * @return summary of what was removed
     */
    public FlushResult onDataChanged(String namespace) {
        return (namespace == null) ? flushAll() : flushNamespace(namespace);
    }

    // ═══════════════════════════════════════════════════════════════
    // Similarity search
    // ═══════════════════════════════════════════════════════════════

    /**
     * Returns the top-K entries most similar to {@code query}, ranked by
     * cosine similarity (highest first), skipping expired entries.
     * Computed in parallel via the internal {@link ForkJoinPool}.
     *
     * @param query query embedding; must have the same dimension as stored entries to match
     * @param topK  maximum number of results to return; must be &gt; 0
     */
    public List<SimilarityResult<M>> searchTopK(float[] query, int topK) {
        validateEmbedding(query);
        if (topK <= 0) throw new IllegalArgumentException("topK must be > 0");

        double queryNorm = computeNorm(query);
        if (queryNorm == 0.0) throw new IllegalArgumentException("Query vector has zero norm");

        List<Entry<M>> snapshot = store.values().stream()
                .filter(e -> !e.isExpired())
                .toList();
        if (snapshot.isEmpty()) return List.of();

        PriorityQueue<SimilarityResult<M>> heap = searchPool.submit(() ->
                snapshot.parallelStream()
                        .filter(e -> e.dimension == query.length)
                        .map(e -> new SimilarityResult<>(e,
                                cosineSimilarity(query, queryNorm, e.embedding, e.norm)))
                        .collect(
                                () -> new PriorityQueue<SimilarityResult<M>>(topK),
                                (pq, r) -> {
                                    if (pq.size() < topK)                      pq.offer(r);
                                    else if (r.score() > pq.peek().score()) {  pq.poll(); pq.offer(r); }
                                },
                                (pq1, pq2) -> pq2.forEach(r -> {
                                    if (pq1.size() < topK)                     pq1.offer(r);
                                    else if (r.score() > pq1.peek().score()) { pq1.poll(); pq1.offer(r); }
                                })
                        )
        ).join();

        List<SimilarityResult<M>> results = new ArrayList<>(heap.size());
        while (!heap.isEmpty()) results.add(heap.poll());
        Collections.reverse(results);

        results.forEach(r -> r.entry().lastAccessedMs = System.currentTimeMillis());
        hitCount.add(results.size());
        return Collections.unmodifiableList(results);
    }

    /**
     * Returns all non-expired entries whose cosine similarity meets or exceeds
     * {@code threshold}, in descending similarity order.
     *
     * @param query     query embedding
     * @param threshold score floor in [-1, 1]
     */
    public List<SimilarityResult<M>> searchByThreshold(float[] query, double threshold) {
        validateEmbedding(query);
        if (threshold < -1.0 || threshold > 1.0)
            throw new IllegalArgumentException("threshold must be in [-1, 1]");

        double queryNorm = computeNorm(query);
        if (queryNorm == 0.0) throw new IllegalArgumentException("Query vector has zero norm");

        List<Entry<M>> snapshot = store.values().stream()
                .filter(e -> !e.isExpired())
                .toList();

        return searchPool.submit(() ->
                snapshot.parallelStream()
                        .filter(e -> e.dimension == query.length)
                        .map(e -> new SimilarityResult<>(e,
                                cosineSimilarity(query, queryNorm, e.embedding, e.norm)))
                        .filter(r -> r.score() >= threshold)
                        .sorted(Comparator.reverseOrder())
                        .toList()
        ).join();
    }

    // ═══════════════════════════════════════════════════════════════
    // Batch operations
    // ═══════════════════════════════════════════════════════════════

    /** Inserts many embeddings sharing the same metadata and namespace in parallel. */
    public void putAll(Map<String, float[]> embeddings, M sharedMetadata, String namespace) {
        embeddings.entrySet().parallelStream()
                .forEach(e -> put(e.getKey(), e.getValue(), sharedMetadata, namespace));
    }

    /** Convenience overload for {@link #putAll} with no namespace. */
    public void putAll(Map<String, float[]> embeddings, M sharedMetadata) {
        putAll(embeddings, sharedMetadata, null);
    }

    /** Returns an unmodifiable view of all stored keys (may include expired ones not yet reaped). */
    public Set<String> ids() { return Collections.unmodifiableSet(store.keySet()); }

    // ═══════════════════════════════════════════════════════════════
    // Stats & diagnostics
    // ═══════════════════════════════════════════════════════════════

    /** A snapshot of runtime counters. */
    public record CacheStats(
            long   hits,
            long   misses,
            long   evictions,
            long   expiryRemovals,
            int    size,
            long   usedMemoryBytes,
            long   maxMemoryBytes,
            double hitRate,
            double memoryUsagePct
    ) {
        @Override
        public String toString() {
            return String.format(
                    "CacheStats{size=%d, hits=%d, misses=%d, evictions=%d, expired=%d, "
                  + "memory=%s / %s (%.1f%%), hitRate=%.2f%%}",
                    size, hits, misses, evictions, expiryRemovals,
                    humanBytes(usedMemoryBytes),
                    maxMemoryBytes == 0 ? "unlimited" : humanBytes(maxMemoryBytes),
                    memoryUsagePct,
                    hitRate * 100);
        }

        private static String humanBytes(long b) {
            if (b < 1024)      return b + " B";
            if (b < 1_048_576) return String.format("%.1f KB", b / 1024.0);
            return String.format("%.2f MB", b / 1_048_576.0);
        }
    }

    public CacheStats stats() {
        long h = hitCount.sum(), m = missCount.sum(), total = h + m;
        long usedMem = usedMemoryBytes.get(), maxMem = config.maxMemoryBytes;
        double memPct = (maxMem > 0) ? (usedMem * 100.0 / maxMem) : 0.0;
        return new CacheStats(h, m, evictions.sum(), expiryRemovals.sum(),
                store.size(), usedMem, maxMem,
                total == 0 ? 0.0 : (double) h / total, memPct);
    }

    /** Current estimated memory usage in bytes. */
    public long usedMemoryBytes() { return usedMemoryBytes.get(); }

    /** The active configuration (read-only). */
    public Config config() { return config; }

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════

    /**
     * Shuts down the background reaper and search thread pool.
     * The cache must not be used after calling this method.
     */
    public void shutdown() {
        if (reaper != null) reaper.shutdownNow();
        searchPool.shutdown();
    }

    // ═══════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════

    /** Background task executed periodically when TTL is enabled. */
    private void reapExpiredEntries() {
        try {
            flushExpired();
        } catch (Exception ignored) {
            // Reaper must never throw — a thrown exception would cancel the ScheduledExecutor
        }
    }

    /** Selects the stripe lock for the given key (by hash). */
    private ReadWriteLock stripe(String id) {
        return stripes[(id.hashCode() & 0x7FFFFFFF) % STRIPE_COUNT];
    }

    /**
     * Evicts LRU entries until both the count limit and the memory limit are satisfied.
     * Must be called before every write, under the relevant stripe write lock.
     */
    private void evictIfNeeded(String incomingId, long incomingBytes) {
        // Fast path: no eviction needed
        boolean countFine = store.size() < config.capacity || store.containsKey(incomingId);
        boolean memFine   = config.maxMemoryBytes == 0
                || (usedMemoryBytes.get() + incomingBytes) <= config.maxMemoryBytes
                || store.containsKey(incomingId);
        if (countFine && memFine) return;

        evictionLock.lock();
        try {
            while (true) {
                boolean cOk = store.size() < config.capacity || store.containsKey(incomingId);
                boolean mOk = config.maxMemoryBytes == 0
                        || (usedMemoryBytes.get() + incomingBytes) <= config.maxMemoryBytes
                        || store.containsKey(incomingId);
                if ((cOk && mOk) || store.isEmpty()) break;

                // Evict the single least-recently-accessed entry
                store.values().stream()
                        .min(Comparator.comparingLong(e -> e.lastAccessedMs))
                        .ifPresent(lru -> {
                            store.remove(lru.id);
                            usedMemoryBytes.addAndGet(-lru.estimatedBytes);
                            evictions.increment();
                        });
            }
        } finally {
            evictionLock.unlock();
        }
    }

    /** Cosine similarity using pre-computed L2 norms. Loop unrolled 4× for SIMD affinity. */
    private static double cosineSimilarity(
            float[] a, double normA, float[] b, double normB) {
        double dot = 0.0;
        int len = a.length, i = 0;
        for (; i <= len - 4; i += 4) {
            dot += (double) a[i]   * b[i]
                 + (double) a[i+1] * b[i+1]
                 + (double) a[i+2] * b[i+2]
                 + (double) a[i+3] * b[i+3];
        }
        for (; i < len; i++) dot += (double) a[i] * b[i];
        return dot / (normA * normB);
    }

    private static double computeNorm(float[] v) {
        double sum = 0.0;
        for (float x : v) sum += (double) x * x;
        return Math.sqrt(sum);
    }

    private static void validateEmbedding(float[] embedding) {
        if (embedding == null || embedding.length == 0)
            throw new IllegalArgumentException("Embedding must be non-null and non-empty");
    }
}