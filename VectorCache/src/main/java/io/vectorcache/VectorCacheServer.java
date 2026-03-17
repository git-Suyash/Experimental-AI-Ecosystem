package io.vectorcache;

import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;
import java.util.stream.*;

/**
 * VectorCacheServer — HTTP REST driver for {@link VectorCache}.
 *
 * <p>Manages a singleton {@link VectorCache} instance. Configure and launch
 * via the fluent {@link Builder}:
 *
 * <pre>{@code
 *   VectorCacheServer server = VectorCacheServer.builder()
 *       .httpPort(8080)
 *       .cacheConfig(VectorCache.Config.builder()
 *           .capacity(50_000)
 *           .maxMemoryMb(512)
 *           .ttlMs(TimeUnit.HOURS.toMillis(1))
 *           .build())
 *       .accessLogPath("logs/vector-cache-access.log")
 *       .accessLogAsync(true)
 *       .build();
 *
 *   server.start();
 * }</pre>
 *
 * <h2>REST Endpoints</h2>
 * <pre>
 *   PUT    /cache/{id}               — store an embedding
 *   GET    /cache/{id}               — retrieve an entry
 *   DELETE /cache/{id}               — remove an entry
 *   POST   /cache/search/topk        — top-K cosine search
 *   POST   /cache/search/threshold   — threshold cosine search
 *   POST   /cache/flush              — flush (all / namespace / expired / custom-age)
 *   POST   /cache/session/start      — onSessionStart lifecycle hook
 *   POST   /cache/data-changed       — onDataChanged lifecycle hook
 *   GET    /cache/stats              — cache statistics
 *   GET    /health                   — liveness probe
 * </pre>
 *
 * <h2>Access Log Format (TSV)</h2>
 * <pre>
 *   timestamp\tmethod\tpath\tkey\toperation\tstatus\tdurationMs\tremoteAddr
 * </pre>
 */
public class VectorCacheServer {

    // ═══════════════════════════════════════════════════════════════
    // Singleton holder
    // ═══════════════════════════════════════════════════════════════

    /** Initialization-on-demand singleton — thread-safe without synchronization. */
    private static volatile VectorCacheServer INSTANCE;

    /** Returns the single server instance; throws if not yet initialized. */
    public static VectorCacheServer getInstance() {
        if (INSTANCE == null)
            throw new IllegalStateException(
                    "VectorCacheServer has not been initialized. Call VectorCacheServer.builder()...build() first.");
        return INSTANCE;
    }

    // ═══════════════════════════════════════════════════════════════
    // Builder / factory
    // ═══════════════════════════════════════════════════════════════

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private int                  httpPort        = 8080;
        private VectorCache.Config   cacheConfig     = VectorCache.Config.builder().build();
        private String               accessLogPath   = "vector-cache-access.log";
        private boolean              accessLogAsync  = true;
        private int                  httpThreads     = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        private boolean              allowSingleton  = true;   // enforce single instance

        /** HTTP port to listen on. Default: 8080. */
        public Builder httpPort(int port)                     { this.httpPort = port;       return this; }

        /** Full {@link VectorCache.Config} for the underlying cache. */
        public Builder cacheConfig(VectorCache.Config cfg)   { this.cacheConfig = cfg;     return this; }

        /** Path (relative or absolute) for the access log file. */
        public Builder accessLogPath(String path)            { this.accessLogPath = path;  return this; }

        /** Write access log entries asynchronously (recommended for production). */
        public Builder accessLogAsync(boolean async)         { this.accessLogAsync = async; return this; }

        /** Number of HTTP handler threads. Default: 2× CPU cores, min 4. */
        public Builder httpThreads(int n)                    { this.httpThreads = n;       return this; }

        /**
         * Builds the server, registers the singleton, and returns it.
         * Throws {@link IllegalStateException} if a singleton already exists and
         * {@code allowSingleton} is true (default).
         */
        public VectorCacheServer build() {
            if (allowSingleton && INSTANCE != null)
                throw new IllegalStateException(
                        "VectorCacheServer singleton already exists. "
                        + "Call VectorCacheServer.getInstance() or shutdown() the existing instance first.");
            VectorCacheServer srv = new VectorCacheServer(this);
            INSTANCE = srv;
            return srv;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Access Logger
    // ═══════════════════════════════════════════════════════════════

    /**
     * Minimalistic TSV access logger.
     *
     * <p>Log line columns (tab-separated):
     * <ol>
     *   <li>timestamp (ISO-8601 ms precision)</li>
     *   <li>HTTP method</li>
     *   <li>request path</li>
     *   <li>key / id extracted from path or body (or "-")</li>
     *   <li>logical operation (PUT, GET, DELETE, SEARCH_TOPK, …)</li>
     *   <li>HTTP status code</li>
     *   <li>duration ms</li>
     *   <li>remote address</li>
     * </ol>
     */
    static final class AccessLogger {

        private static final DateTimeFormatter TS_FMT =
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                        .withZone(ZoneOffset.UTC);

        private static final String HEADER =
                "timestamp\tmethod\tpath\tkey\toperation\tstatus\tdurationMs\tremoteAddr";

        private final Path               logPath;
        private final boolean            async;
        private final ExecutorService    writer;   // single-thread for async writes
        private final AtomicLong         lineCount = new AtomicLong();

        AccessLogger(String logPath, boolean async) {
            this.logPath = Path.of(logPath);
            this.async   = async;
            this.writer  = async
                    ? Executors.newSingleThreadExecutor(r -> {
                          Thread t = new Thread(r, "AccessLog-Writer");
                          t.setDaemon(true);
                          return t;
                      })
                    : null;
            initFile();
        }

        private void initFile() {
            try {
                Path parent = logPath.getParent();
                if (parent != null) Files.createDirectories(parent);
                if (!Files.exists(logPath)) {
                    Files.writeString(logPath, HEADER + System.lineSeparator(),
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                }
            } catch (IOException e) {
                System.err.println("[AccessLogger] Could not init log file: " + e.getMessage());
            }
        }

        void log(String method, String path, String key, String operation,
                 int status, long durationMs, String remoteAddr) {
            String line = String.join("\t",
                    TS_FMT.format(Instant.now()),
                    method,
                    path,
                    key == null || key.isBlank() ? "-" : key,
                    operation,
                    String.valueOf(status),
                    String.valueOf(durationMs),
                    remoteAddr
            ) + System.lineSeparator();

            if (async && writer != null) {
                writer.submit(() -> appendLine(line));
            } else {
                appendLine(line);
            }
            lineCount.incrementAndGet();
        }

        private void appendLine(String line) {
            try {
                Files.writeString(logPath, line,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE);
            } catch (IOException e) {
                System.err.println("[AccessLogger] Write failed: " + e.getMessage());
            }
        }

        long lineCount()  { return lineCount.get(); }
        String logPath()  { return logPath.toAbsolutePath().toString(); }

        void shutdown() {
            if (writer != null) {
                writer.shutdown();
                try { writer.awaitTermination(3, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Minimal JSON helpers (zero external dependencies)
    // ═══════════════════════════════════════════════════════════════

    /** Barebone JSON builder — avoids pulling in Jackson/Gson. */
    private static final class Json {

        static String obj(Object... kvPairs) {
            if (kvPairs.length % 2 != 0) throw new IllegalArgumentException("Pairs required");
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < kvPairs.length; i += 2) {
                if (i > 0) sb.append(',');
                sb.append('"').append(kvPairs[i]).append("\":");
                Object v = kvPairs[i + 1];
                if      (v == null)             sb.append("null");
                else if (v instanceof Number)   sb.append(v);
                else if (v instanceof Boolean)  sb.append(v);
                else if (v instanceof String s) sb.append('"').append(escape(s)).append('"');
                else                            sb.append(v);  // pre-built JSON fragment
            }
            return sb.append('}').toString();
        }

        static String arr(List<String> items) {
            return "[" + String.join(",", items) + "]";
        }

        static String escape(String s) {
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }

        /** Parse a named string field from a JSON-ish string — good enough for internal use. */
        static String strField(String json, String key) {
            String pattern = "\"" + key + "\"";
            int ki = json.indexOf(pattern);
            if (ki < 0) return null;
            int colon = json.indexOf(':', ki + pattern.length());
            if (colon < 0) return null;
            int q1 = json.indexOf('"', colon + 1);
            if (q1 < 0) return null;
            int q2 = json.indexOf('"', q1 + 1);
            if (q2 < 0) return null;
            return json.substring(q1 + 1, q2);
        }

        /** Parse a named float-array field, e.g. "embedding":[1.0,2.0,3.0]. */
        static float[] floatArrayField(String json, String key) {
            String pattern = "\"" + key + "\"";
            int ki = json.indexOf(pattern);
            if (ki < 0) return null;
            int ab = json.indexOf('[', ki + pattern.length());
            if (ab < 0) return null;
            int ae = json.indexOf(']', ab);
            if (ae < 0) return null;
            String[] parts = json.substring(ab + 1, ae).split(",");
            float[] result = new float[parts.length];
            for (int i = 0; i < parts.length; i++)
                result[i] = Float.parseFloat(parts[i].trim());
            return result;
        }

        /** Parse a named int field. Returns defaultValue if not found. */
        static int intField(String json, String key, int defaultValue) {
            String pattern = "\"" + key + "\"";
            int ki = json.indexOf(pattern);
            if (ki < 0) return defaultValue;
            int colon = json.indexOf(':', ki + pattern.length());
            if (colon < 0) return defaultValue;
            StringBuilder sb = new StringBuilder();
            for (int i = colon + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (Character.isDigit(c) || c == '-') sb.append(c);
                else if (!sb.isEmpty()) break;
            }
            return sb.isEmpty() ? defaultValue : Integer.parseInt(sb.toString());
        }

        /** Parse a named double field. Returns defaultValue if not found. */
        static double doubleField(String json, String key, double defaultValue) {
            String pattern = "\"" + key + "\"";
            int ki = json.indexOf(pattern);
            if (ki < 0) return defaultValue;
            int colon = json.indexOf(':', ki + pattern.length());
            if (colon < 0) return defaultValue;
            StringBuilder sb = new StringBuilder();
            for (int i = colon + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (Character.isDigit(c) || c == '-' || c == '.') sb.append(c);
                else if (!sb.isEmpty()) break;
            }
            return sb.isEmpty() ? defaultValue : Double.parseDouble(sb.toString());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Base HTTP handler
    // ═══════════════════════════════════════════════════════════════

    /**
     * Base handler — handles CORS, JSON content-type, access logging, timing,
     * and error wrapping. Subclasses implement {@code handle(ctx)}.
     */
    abstract class BaseHandler implements HttpHandler {
        private final String operation;

        BaseHandler(String operation) { this.operation = operation; }

        @Override
        public final void handle(HttpExchange ex) throws IOException {
            long start = System.currentTimeMillis();
            String key = "-";
            int status = 200;
            try {
                // CORS
                ex.getResponseHeaders().add("Access-Control-Allow-Origin",  "*");
                ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
                ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
                ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");

                if ("OPTIONS".equals(ex.getRequestMethod())) {
                    send(ex, 204, "");
                    return;
                }

                HandlerContext ctx = new HandlerContext(ex);
                key = ctx.pathKey();
                HandlerResult result = handleRequest(ctx);
                status = result.status;
                send(ex, result.status, result.body);
            } catch (IllegalArgumentException e) {
                status = 400;
                send(ex, 400, Json.obj("error", e.getMessage()));
            } catch (Exception e) {
                status = 500;
                send(ex, 500, Json.obj("error", "Internal server error: " + e.getMessage()));
            } finally {
                long dur = System.currentTimeMillis() - start;
                accessLogger.log(
                        ex.getRequestMethod(),
                        ex.getRequestURI().getPath(),
                        key, operation, status, dur,
                        ex.getRemoteAddress() != null
                                ? ex.getRemoteAddress().toString() : "-");
            }
        }

        abstract HandlerResult handleRequest(HandlerContext ctx) throws Exception;

        private void send(HttpExchange ex, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HandlerContext & HandlerResult helpers
    // ═══════════════════════════════════════════════════════════════

    static final class HandlerContext {
        final HttpExchange ex;
        final String       method;
        final String       path;
        final String       body;

        HandlerContext(HttpExchange ex) throws IOException {
            this.ex     = ex;
            this.method = ex.getRequestMethod();
            this.path   = ex.getRequestURI().getPath();
            this.body   = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        }

        /** Extracts the last path segment as the cache key. */
        String pathKey() {
            String[] parts = path.split("/");
            return parts.length > 0 ? parts[parts.length - 1] : "-";
        }
    }

    record HandlerResult(int status, String body) {
        static HandlerResult ok(String body)      { return new HandlerResult(200, body); }
        static HandlerResult created(String body) { return new HandlerResult(201, body); }
        static HandlerResult noContent()          { return new HandlerResult(204, ""); }
        static HandlerResult notFound(String msg) { return new HandlerResult(404, Json.obj("error", msg)); }
        static HandlerResult badRequest(String m) { return new HandlerResult(400, Json.obj("error", m)); }
    }

    // ═══════════════════════════════════════════════════════════════
    // Fields
    // ═══════════════════════════════════════════════════════════════

    private final VectorCache<Map<String, String>> cache;
    private final HttpServer                       httpServer;
    private final AccessLogger                     accessLogger;
    private final int                              httpPort;
    private volatile boolean                       running = false;

    // ═══════════════════════════════════════════════════════════════
    // Private constructor (use Builder)
    // ═══════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    private VectorCacheServer(Builder b) {
        this.httpPort     = b.httpPort;
        this.cache        = new VectorCache<>(b.cacheConfig);
        this.accessLogger = new AccessLogger(b.accessLogPath, b.accessLogAsync);

        try {
            this.httpServer = HttpServer.create(new InetSocketAddress(b.httpPort), 0);
        } catch (IOException e) {
            throw new RuntimeException("Cannot bind HTTP server on port " + b.httpPort, e);
        }

        registerRoutes();
        httpServer.setExecutor(Executors.newFixedThreadPool(b.httpThreads, r -> {
            Thread t = new Thread(r, "http-worker-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        }));
    }

    // ═══════════════════════════════════════════════════════════════
    // Route registration
    // ═══════════════════════════════════════════════════════════════

    private void registerRoutes() {
        // /cache/{id}  — GET / PUT / DELETE
        httpServer.createContext("/cache/", new BaseHandler("CRUD") {
            @Override HandlerResult handleRequest(HandlerContext ctx) {
                // Delegate to sub-path specific methods
                String path = ctx.path;
                if (path.startsWith("/cache/search/topk"))      return handleSearchTopK(ctx);
                if (path.startsWith("/cache/search/threshold")) return handleSearchThreshold(ctx);
                if (path.startsWith("/cache/flush"))            return handleFlush(ctx);
                if (path.startsWith("/cache/session/start"))    return handleSessionStart(ctx);
                if (path.startsWith("/cache/data-changed"))     return handleDataChanged(ctx);
                if (path.equals("/cache/stats"))                return handleStats(ctx);

                // Key-level operations
                String key = extractKey(ctx.path, "/cache/");
                if (key == null || key.isBlank())
                    return HandlerResult.badRequest("Missing key in path");

                return switch (ctx.method) {
                    case "PUT"    -> handlePut(ctx, key);
                    case "GET"    -> handleGet(ctx, key);
                    case "DELETE" -> handleDelete(ctx, key);
                    default       -> new HandlerResult(405, Json.obj("error", "Method not allowed"));
                };
            }
        });

        // /health
        httpServer.createContext("/health", new BaseHandler("HEALTH") {
            @Override HandlerResult handleRequest(HandlerContext ctx) {
                return HandlerResult.ok(Json.obj(
                        "status", "UP",
                        "cacheSize", cache.size(),
                        "usedMemoryBytes", cache.usedMemoryBytes(),
                        "timestamp", Instant.now().toString()
                ));
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    // Route handlers
    // ═══════════════════════════════════════════════════════════════

    /**
     * PUT /cache/{id}
     * Body: { "embedding": [f1,f2,...], "namespace": "ns", "meta_key": "val", ... }
     */
    private HandlerResult handlePut(HandlerContext ctx, String key) {
        float[] embedding = Json.floatArrayField(ctx.body, "embedding");
        if (embedding == null || embedding.length == 0)
            return HandlerResult.badRequest("'embedding' float array is required");

        String namespace = Json.strField(ctx.body, "namespace");

        // Collect all remaining string fields as metadata map
        Map<String, String> meta = new LinkedHashMap<>();
        extractMetaFields(ctx.body, meta, "embedding", "namespace");

        cache.put(key, embedding, meta, namespace);
        return HandlerResult.created(Json.obj(
                "id", key,
                "dimension", embedding.length,
                "namespace", namespace != null ? namespace : "",
                "status", "stored"));
    }

    /**
     * GET /cache/{id}
     * Returns the entry or 404 if not found / expired.
     */
    private HandlerResult handleGet(HandlerContext ctx, String key) {
        return cache.get(key)
                .map(e -> HandlerResult.ok(entryToJson(e)))
                .orElseGet(() -> HandlerResult.notFound("Key not found or expired: " + key));
    }

    /**
     * DELETE /cache/{id}
     */
    private HandlerResult handleDelete(HandlerContext ctx, String key) {
        return cache.remove(key)
                .map(e -> HandlerResult.ok(Json.obj("id", key, "status", "removed")))
                .orElseGet(() -> HandlerResult.notFound("Key not found: " + key));
    }

    /**
     * POST /cache/search/topk
     * Body: { "embedding": [...], "topK": 5 }
     */
    private HandlerResult handleSearchTopK(HandlerContext ctx) {
        float[] query = Json.floatArrayField(ctx.body, "embedding");
        if (query == null) return HandlerResult.badRequest("'embedding' is required");
        int topK = Json.intField(ctx.body, "topK", 10);

        List<VectorCache.SimilarityResult<Map<String, String>>> results =
                cache.searchTopK(query, topK);

        List<String> items = results.stream()
                .map(r -> Json.obj(
                        "id",    r.entry().getId(),
                        "score", r.score(),
                        "namespace", r.entry().getNamespace() != null ? r.entry().getNamespace() : "",
                        "ttlRemainingMs", r.entry().remainingTtlMs(),
                        "meta",  metaToJson(r.entry().getMetadata())))
                .toList();

        return HandlerResult.ok(Json.obj(
                "query_dimension", query.length,
                "results", Json.arr(items),
                "count", results.size()));
    }

    /**
     * POST /cache/search/threshold
     * Body: { "embedding": [...], "threshold": 0.85 }
     */
    private HandlerResult handleSearchThreshold(HandlerContext ctx) {
        float[] query = Json.floatArrayField(ctx.body, "embedding");
        if (query == null) return HandlerResult.badRequest("'embedding' is required");
        double threshold = Json.doubleField(ctx.body, "threshold", 0.8);

        List<VectorCache.SimilarityResult<Map<String, String>>> results =
                cache.searchByThreshold(query, threshold);

        List<String> items = results.stream()
                .map(r -> Json.obj(
                        "id",    r.entry().getId(),
                        "score", r.score(),
                        "namespace", r.entry().getNamespace() != null ? r.entry().getNamespace() : "",
                        "ttlRemainingMs", r.entry().remainingTtlMs(),
                        "meta",  metaToJson(r.entry().getMetadata())))
                .toList();

        return HandlerResult.ok(Json.obj(
                "threshold", threshold,
                "results", Json.arr(items),
                "count", results.size()));
    }

    /**
     * POST /cache/flush
     * Body: { "mode": "all"|"namespace"|"expired"|"older_than_ms" , "namespace": "ns", "olderThanMs": 300000 }
     */
    private HandlerResult handleFlush(HandlerContext ctx) {
        String mode = Json.strField(ctx.body, "mode");
        if (mode == null) mode = "all";

        VectorCache.FlushResult result = switch (mode) {
            case "namespace" -> {
                String ns = Json.strField(ctx.body, "namespace");
                if (ns == null) yield null;
                yield cache.flushNamespace(ns);
            }
            case "expired"   -> cache.flushExpired();
            case "older_than_ms" -> {
                long ms = (long) Json.doubleField(ctx.body, "olderThanMs", 600_000);
                long cutoff = System.currentTimeMillis() - ms;
                yield cache.flushWhere(
                        e -> e.getInsertedAtMs() < cutoff,
                        "flush_older_than_" + ms + "ms");
            }
            default -> cache.flushAll();
        };

        if (result == null)
            return HandlerResult.badRequest("'namespace' is required for mode=namespace");

        return HandlerResult.ok(Json.obj(
                "mode", mode,
                "removedCount", result.removedCount(),
                "freedBytes", result.freedBytes(),
                "reason", result.reason()));
    }

    /**
     * POST /cache/session/start
     * Body: { "sessionId": "user-42" }
     */
    private HandlerResult handleSessionStart(HandlerContext ctx) {
        String sessionId = Json.strField(ctx.body, "sessionId");
        if (sessionId == null || sessionId.isBlank())
            return HandlerResult.badRequest("'sessionId' is required");

        VectorCache.FlushResult result = cache.onSessionStart(sessionId);
        return HandlerResult.ok(Json.obj(
                "sessionId", sessionId,
                "expiredRemoved", result.removedCount(),
                "freedBytes", result.freedBytes(),
                "statsReset", true));
    }

    /**
     * POST /cache/data-changed
     * Body: { "namespace": "products" }  — omit or null for full flush
     */
    private HandlerResult handleDataChanged(HandlerContext ctx) {
        String namespace = Json.strField(ctx.body, "namespace");
        VectorCache.FlushResult result = cache.onDataChanged(namespace);
        return HandlerResult.ok(Json.obj(
                "namespace", namespace != null ? namespace : "(all)",
                "removedCount", result.removedCount(),
                "freedBytes", result.freedBytes(),
                "reason", result.reason()));
    }

    /**
     * GET /cache/stats
     */
    private HandlerResult handleStats(HandlerContext ctx) {
        VectorCache.CacheStats s = cache.stats();
        return HandlerResult.ok(Json.obj(
                "size", s.size(),
                "hits", s.hits(),
                "misses", s.misses(),
                "evictions", s.evictions(),
                "expiryRemovals", s.expiryRemovals(),
                "usedMemoryBytes", s.usedMemoryBytes(),
                "maxMemoryBytes", s.maxMemoryBytes(),
                "memoryUsagePct", Math.round(s.memoryUsagePct() * 10.0) / 10.0,
                "hitRate", Math.round(s.hitRate() * 10000.0) / 100.0,
                "accessLogLines", accessLogger.lineCount(),
                "accessLogPath", accessLogger.logPath()));
    }

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════

    /** Starts the HTTP server and registers a JVM shutdown hook. */
    public VectorCacheServer start() {
        if (running) throw new IllegalStateException("Server is already running");
        httpServer.start();
        running = true;
        System.out.printf("[VectorCacheServer] HTTP listening on http://0.0.0.0:%d%n", httpPort);
        System.out.printf("[VectorCacheServer] Access log → %s%n", accessLogger.logPath());
        System.out.printf("[VectorCacheServer] Cache config → %s%n", cache.config());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[VectorCacheServer] Shutdown hook triggered…");
            shutdown();
        }, "VectorCacheServer-ShutdownHook"));

        return this;
    }

    /** Gracefully stops the server and releases all resources. */
    public void shutdown() {
        if (!running) return;
        running = false;
        httpServer.stop(3);       // 3s grace period for in-flight requests
        cache.shutdown();
        accessLogger.shutdown();
        INSTANCE = null;
        System.out.println("[VectorCacheServer] Shutdown complete.");
    }

    public boolean isRunning()            { return running; }
    public int     httpPort()             { return httpPort; }
    public VectorCache<Map<String,String>> cache() { return cache; }

    // ═══════════════════════════════════════════════════════════════
    // Private utilities
    // ═══════════════════════════════════════════════════════════════

    private static String extractKey(String path, String prefix) {
        if (!path.startsWith(prefix)) return null;
        String tail = path.substring(prefix.length());
        // Strip trailing slash if any
        if (tail.endsWith("/")) tail = tail.substring(0, tail.length() - 1);
        return tail;
    }

    private static String entryToJson(VectorCache.Entry<Map<String, String>> e) {
        String embJson = "[" + floatsToString(e.getEmbedding()) + "]";
        return Json.obj(
                "id", e.getId(),
                "dimension", e.getDimension(),
                "norm", Math.round(e.getNorm() * 1e6) / 1e6,
                "namespace", e.getNamespace() != null ? e.getNamespace() : "",
                "insertedAtMs", e.getInsertedAtMs(),
                "lastAccessedMs", e.getLastAccessedMs(),
                "ttlRemainingMs", e.remainingTtlMs(),
                "embedding", embJson,
                "meta", metaToJson(e.getMetadata()));
    }

    private static String metaToJson(Map<String, String> meta) {
        if (meta == null || meta.isEmpty()) return "{}";
        String inner = meta.entrySet().stream()
                .map(e -> "\"" + Json.escape(e.getKey()) + "\":\"" + Json.escape(e.getValue()) + "\"")
                .collect(Collectors.joining(","));
        return "{" + inner + "}";
    }

    private static String floatsToString(float[] arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    /**
     * Scans the raw JSON body for top-level string fields and adds them to {@code target},
     * skipping fields named in {@code skip}.
     */
    private static void extractMetaFields(String json, Map<String, String> target, String... skip) {
        Set<String> skipSet = Set.of(skip);
        // Simple regex-free scan: find all "key":"value" pairs
        int i = 0;
        while (i < json.length()) {
            int ks = json.indexOf('"', i);
            if (ks < 0) break;
            int ke = json.indexOf('"', ks + 1);
            if (ke < 0) break;
            String fieldName = json.substring(ks + 1, ke);
            int colon = json.indexOf(':', ke + 1);
            if (colon < 0) break;
            // Skip whitespace
            int vs = colon + 1;
            while (vs < json.length() && Character.isWhitespace(json.charAt(vs))) vs++;
            if (vs >= json.length()) break;
            char firstChar = json.charAt(vs);
            if (firstChar == '"') {
                int ve = json.indexOf('"', vs + 1);
                if (ve < 0) break;
                String val = json.substring(vs + 1, ve);
                if (!skipSet.contains(fieldName) && !fieldName.isBlank())
                    target.put(fieldName, val);
                i = ve + 1;
            } else {
                // Not a string value — skip past it
                i = colon + 1;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Entry point
    // ═══════════════════════════════════════════════════════════════

    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        VectorCacheServer server = VectorCacheServer.builder()
                .httpPort(port)
                .cacheConfig(VectorCache.Config.builder()
                        .capacity(50_000)
                        .maxMemoryMb(256)
                        .ttlMs(TimeUnit.MINUTES.toMillis(30))
                        .reaperIntervalMs(TimeUnit.MINUTES.toMillis(2))
                        .parallelism(Runtime.getRuntime().availableProcessors())
                        .build())
                .accessLogPath("logs/vector-cache-access.log")
                .accessLogAsync(true)
                .httpThreads(8)
                .build()
                .start();

        System.out.println("\n── Quick-smoke using the running server ──");
        Thread.sleep(200); // tiny pause for server to bind
        String base = "http://localhost:" + port;
        try {
            httpPut(base + "/cache/doc:1",
                    "{\"embedding\":[1.0,0.8,0.1,0.2],\"namespace\":\"docs\",\"label\":\"fruit\"}");
            httpPut(base + "/cache/doc:2",
                    "{\"embedding\":[0.1,0.2,0.9,0.8],\"namespace\":\"docs\",\"label\":\"vehicle\"}");
            System.out.println("PUT doc:1, doc:2 -> OK");
            String got = httpGet(base + "/cache/doc:1");
            System.out.println("GET doc:1 -> " + got.substring(0, Math.min(120, got.length())));
            String topk = httpPost(base + "/cache/search/topk",
                    "{\"embedding\":[1.0,0.9,0.05,0.1],\"topK\":2}");
            System.out.println("SEARCH topK=2 -> " + topk.substring(0, Math.min(160, topk.length())));
            System.out.println("STATS -> " + httpGet(base + "/cache/stats"));
            System.out.println("HEALTH -> " + httpGet(base + "/health"));
            System.out.println("DATA-CHANGED -> " + httpPost(base + "/cache/data-changed",
                    "{\"namespace\":\"docs\"}"));
        } catch (Exception e) {
            System.err.println("Smoke-test error: " + e.getMessage());
        }
        System.out.println("\nServer running. Press Ctrl+C to stop.");
        Thread.currentThread().join(); // block until shutdown hook fires
    }

    // ── tiny HTTP client helpers for the demo ────────────────────────

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        return readResponse(c);
    }

    private static String httpPut(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("PUT");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        return readResponse(c);
    }

    private static String httpPost(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        return readResponse(c);
    }

    private static String readResponse(HttpURLConnection c) throws Exception {
        InputStream is = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }
}
