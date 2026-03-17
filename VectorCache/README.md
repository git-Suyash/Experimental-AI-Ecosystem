# VectorCache

Thread-safe, high-performance in-memory vector cache with cosine-similarity
search, TTL, LRU/memory-ceiling eviction, and an embedded HTTP REST server.
Zero runtime dependencies — ships as a plain JDK 21 JAR.

---

## Contents

- [Artifacts produced](#artifacts-produced)
- [Quick start — as a library dependency](#quick-start--as-a-library-dependency)
- [Quick start — as a standalone service](#quick-start--as-a-standalone-service)
- [Maven commands](#maven-commands)
- [Docker deployment](#docker-deployment)
- [Publishing to a repository](#publishing-to-a-repository)
- [REST API reference](#rest-api-reference)
- [Project layout](#project-layout)

---

## Artifacts produced

Running `mvn package` produces four artifacts in `target/`:

| File | Purpose |
|------|---------|
| `vectorcache-1.0.0.jar` | **Thin library JAR** — add as a Maven dependency |
| `vectorcache-1.0.0-server.jar` | **Fat executable JAR** — deploy as a standalone service |
| `vectorcache-1.0.0-sources.jar` | Source attachment for IDEs |
| `vectorcache-1.0.0-javadoc.jar` | Javadoc attachment (required for Central) |

---

## Quick start — as a library dependency

### 1. Install to your local Maven repository

```bash
cd vectorcache
mvn install -DskipTests
```

### 2. Add the dependency to your project's `pom.xml`

```xml
<dependency>
  <groupId>io.vectorcache</groupId>
  <artifactId>vectorcache</artifactId>
  <version>1.0.0</version>
</dependency>
```

### 3. Use the cache in your code

```java
import io.vectorcache.VectorCache;
import io.vectorcache.VectorCacheServer;

// ── Embedded cache (no HTTP, pure library) ────────────────────────
var cache = new VectorCache<>(VectorCache.Config.builder()
    .capacity(50_000)
    .maxMemoryMb(512)
    .ttlMs(TimeUnit.HOURS.toMillis(1))
    .parallelism(4)
    .build());

cache.put("doc:1", new float[]{0.1f, 0.9f, 0.3f}, myMeta, "products");
Optional<VectorCache.Entry<M>> entry = cache.get("doc:1");
List<VectorCache.SimilarityResult<M>> results = cache.searchTopK(queryVector, 10);

cache.onDataChanged("products");   // invalidate a namespace
cache.onSessionStart("user-42");   // reap expired + reset stats
cache.shutdown();

// ── Embedded HTTP server (cache + REST endpoints) ─────────────────
VectorCacheServer server = VectorCacheServer.builder()
    .httpPort(8080)
    .cacheConfig(VectorCache.Config.builder().capacity(10_000).build())
    .accessLogPath("logs/access.log")
    .build()
    .start();

// Retrieve singleton anywhere in the same JVM
VectorCacheServer srv = VectorCacheServer.getInstance();
srv.shutdown();
```

---

## Quick start — as a standalone service

### Run directly from the fat JAR

```bash
# Build first
mvn package -DskipTests

# Run server on default port 8080
java -jar target/vectorcache-1.0.0-server.jar server

# Run server on custom port
java -jar target/vectorcache-1.0.0-server.jar server 9090

# Run library demo
java -jar target/vectorcache-1.0.0-server.jar library

# Recommended JVM flags for production
java \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+ExitOnOutOfMemoryError \
  -jar target/vectorcache-1.0.0-server.jar server 8080
```

---

## Maven commands

```bash
# Compile only
mvn compile

# Compile + run tests
mvn verify

# Package all artifacts (thin jar, fat jar, sources, javadoc)
mvn package -DskipTests

# Install thin library JAR to local ~/.m2 repository
mvn install -DskipTests

# Deploy to remote repository (configure distributionManagement in pom.xml first)
mvn deploy -DskipTests

# Deploy + sign artifacts for Maven Central
mvn deploy -P release

# Build Docker image  (requires Docker daemon)
mvn package -P docker -DskipTests

# Run tests only
mvn verify -pl .
```

---

## Docker deployment

### Build the image

```bash
# Via Maven profile (recommended — version tag is automatic)
mvn package -P docker -DskipTests

# Or manually
docker build -f docker/Dockerfile -t vectorcache:1.0.0 -t vectorcache:latest .
```

### Run the container

```bash
# Basic run
docker run -p 8080:8080 vectorcache:latest server

# With persistent access logs
docker run -p 8080:8080 \
  -v /host/path/logs:/app/logs \
  vectorcache:latest server

# Production run with resource limits
docker run \
  -p 8080:8080 \
  -m 1g \
  --cpus="2" \
  -v /var/log/vectorcache:/app/logs \
  vectorcache:latest server
```

### Docker Compose

```yaml
services:
  vectorcache:
    image: vectorcache:1.0.0
    command: ["server"]
    ports:
      - "8080:8080"
    volumes:
      - ./logs:/app/logs
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:8080/health"]
      interval: 30s
      timeout: 5s
      retries: 3
    restart: unless-stopped
```

---

## Publishing to a repository

### Option A — GitHub Packages

1. Add to `pom.xml` `<distributionManagement>`:

```xml
<repository>
  <id>github</id>
  <url>https://maven.pkg.github.com/YOUR_ORG/vectorcache</url>
</repository>
```

2. Add credentials to `~/.m2/settings.xml` (see `settings.xml.template`):

```xml
<server>
  <id>github</id>
  <username>YOUR_GITHUB_USER</username>
  <password>YOUR_GITHUB_PAT</password>
</server>
```

3. Deploy:

```bash
mvn deploy -DskipTests
```

### Option B — Private Nexus / Artifactory

Update `<distributionManagement>` URLs in `pom.xml` to point to your instance,
add `<server>` credentials to `~/.m2/settings.xml`, then run `mvn deploy`.

### Option C — Maven Central (OSSRH)

1. Register at https://central.sonatype.org
2. Fill in Sonatype credentials in `~/.m2/settings.xml`
3. Configure your GPG key (see `settings.xml.template`)
4. Bump version to a non-SNAPSHOT (e.g. `1.0.0`)
5. Run:

```bash
mvn deploy -P release
```

---

## REST API reference

All endpoints return `application/json`. Base URL: `http://host:port`

### Store an embedding
```
PUT /cache/{id}
Body: {
  "embedding":  [1.0, 0.8, 0.1, 0.2],   // required — float array
  "namespace":  "products",               // optional
  "label":      "apple"                   // any extra string fields → metadata
}
Response 201: { "id": "...", "dimension": 4, "status": "stored" }
```

### Retrieve an entry
```
GET /cache/{id}
Response 200: { "id", "embedding", "dimension", "namespace",
                "insertedAtMs", "ttlRemainingMs", "meta": {} }
Response 404: { "error": "..." }
```

### Delete an entry
```
DELETE /cache/{id}
Response 200: { "id": "...", "status": "removed" }
Response 404: { "error": "..." }
```

### Top-K similarity search
```
POST /cache/search/topk
Body: { "embedding": [...], "topK": 10 }
Response 200: { "results": [...], "count": N }
```

### Threshold search
```
POST /cache/search/threshold
Body: { "embedding": [...], "threshold": 0.85 }
Response 200: { "results": [...], "count": N }
```

### Flush
```
POST /cache/flush
Body: { "mode": "all" }                              // wipe everything
Body: { "mode": "namespace", "namespace": "ns" }    // one namespace
Body: { "mode": "expired" }                          // TTL-exceeded only
Body: { "mode": "older_than_ms", "olderThanMs": 600000 }  // age-based
Response 200: { "removedCount": N, "freedBytes": N }
```

### Session start
```
POST /cache/session/start
Body: { "sessionId": "user-42" }
Response 200: { "expiredRemoved": N, "statsReset": true }
```

### Data changed
```
POST /cache/data-changed
Body: { "namespace": "products" }   // flush one namespace
Body: {}                             // flush everything
Response 200: { "removedCount": N }
```

### Stats
```
GET /cache/stats
Response 200: { "size", "hits", "misses", "evictions", "expiryRemovals",
                "usedMemoryBytes", "maxMemoryBytes", "memoryUsagePct",
                "hitRate", "accessLogLines", "accessLogPath" }
```

### Health
```
GET /health
Response 200: { "status": "UP", "cacheSize": N }
```

---

## Project layout

```
vectorcache/
├── pom.xml                          # Build descriptor
├── settings.xml.template            # Copy to ~/.m2/settings.xml
├── docker/
│   └── Dockerfile                   # Multi-stage: builder + slim runtime
├── src/
│   ├── main/java/io/vectorcache/
│   │   ├── VectorCache.java         # Core cache (use as a library)
│   │   └── VectorCacheServer.java   # HTTP server driver (singleton)
│   └── test/java/io/vectorcache/
│       └── VectorCacheTestSuite.java # 61-test self-contained suite
└── target/                          # Generated by Maven (git-ignored)
    ├── vectorcache-1.0.0.jar            # Thin library JAR
    ├── vectorcache-1.0.0-server.jar     # Executable fat-JAR
    ├── vectorcache-1.0.0-sources.jar    # Source attachment
    └── vectorcache-1.0.0-javadoc.jar    # Javadoc attachment
```
