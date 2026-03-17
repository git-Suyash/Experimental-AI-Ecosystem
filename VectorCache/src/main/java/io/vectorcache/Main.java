package io.vectorcache;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Main entry point for the VectorCache repository.
 * Provides options to run in library mode (direct VectorCache usage) or server mode (HTTP REST API).
 *
 * Usage:
 *   java -cp target/classes io.vectorcache.Main library    # Run library demo
 *   java -cp target/classes io.vectorcache.Main server     # Run server with demo
 *   java -cp target/classes io.vectorcache.Main server 9090 # Run server on custom port
 */
public class Main {

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String mode = args[0].toLowerCase();

        switch (mode) {
            case "library" -> runLibraryDemo();
            case "server" -> {
                int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
                runServerDemo(port);
            }
            default -> {
                System.err.println("Unknown mode: " + mode);
                printUsage();
            }
        }
    }

    private static void printUsage() {
        System.out.println("VectorCache Main Entry Point");
        System.out.println("Usage:");
        System.out.println("  java io.vectorcache.Main library          # Run library demo");
        System.out.println("  java io.vectorcache.Main server [port]    # Run server demo (default port 8080)");
    }

    /**
     * Demo for VectorCache library usage (extracted from original VectorCache.main).
     */
    private static void runLibraryDemo() {
        System.out.println("=== VectorCache Library Demo ===");

        // Create cache with custom config
        var cache = new VectorCache<Map<String, String>>(VectorCache.Config.builder()
                .capacity(100)
                .maxMemoryMb(64)
                .ttlMs(TimeUnit.MINUTES.toMillis(5))
                .parallelism(2)
                .build());

        // Sample embeddings (3D vectors)
        float[] vec1 = {1.0f, 0.0f, 0.0f};
        float[] vec2 = {0.0f, 1.0f, 0.0f};
        float[] vec3 = {0.5f, 0.5f, 0.0f};
        float[] query = {0.9f, 0.1f, 0.0f};

        // Put some data
        cache.put("red", vec1, Map.of("color", "red"), "colors");
        cache.put("green", vec2, Map.of("color", "green"), "colors");
        cache.put("yellow", vec3, Map.of("color", "yellow"), "colors");

        System.out.println("Stored 3 vectors in cache");

        // Search top-2 similar to query
        var results = cache.searchTopK(query, 2);
        System.out.println("Top-2 results for query " + java.util.Arrays.toString(query) + ":");
        for (var r : results) {
            System.out.printf("  %s: %.3f (color: %s)%n",
                    r.entry().getId(),
                    r.score(),
                    r.entry().getMetadata().get("color"));
        }

        // Stats
        var stats = cache.stats();
        System.out.printf("Cache stats: size=%d, hits=%d, misses=%d, hitRate=%.1f%%%n",
                stats.size(), stats.hits(), stats.misses(), stats.hitRate());

        cache.shutdown();
        System.out.println("Library demo complete.");
    }

    /**
     * Demo for VectorCacheServer (extracted from original VectorCacheServer.main).
     */
    private static void runServerDemo(int port) {
        System.out.println("=== VectorCache Server Demo ===");

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

        System.out.println("\n── Quick-smoke test using the running server ──");
        try {
            Thread.sleep(200); // tiny pause for server to bind
            String base = "http://localhost:" + port;

            // Put a test embedding
            String putResponse = httpPut(base + "/cache/test1",
                    "{\"embedding\": [1.0, 0.8, 0.1], \"namespace\": \"demo\", \"label\": \"test\"}");
            System.out.println("PUT /cache/test1: " + putResponse);

            // Get it back
            String getResponse = httpGet(base + "/cache/test1");
            System.out.println("GET /cache/test1: " + getResponse);

            // Search
            String searchResponse = httpPost(base + "/cache/search/topk",
                    "{\"embedding\": [0.9, 0.7, 0.2], \"topK\": 5}");
            System.out.println("POST /cache/search/topk: " + searchResponse);

            // Stats
            String statsResponse = httpGet(base + "/cache/stats");
            System.out.println("GET /cache/stats: " + statsResponse);

        } catch (Exception e) {
            System.err.println("Demo test failed: " + e.getMessage());
        }

        System.out.println("\nServer running on port " + port + ". Press Ctrl+C to stop.");
        try {
            Thread.currentThread().join(); // block until shutdown hook fires
        } catch (InterruptedException e) {
            server.shutdown();
        }
    }

    // Tiny HTTP client helpers (copied from VectorCacheServer)
    private static String httpGet(String url) throws Exception {
        var c = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        c.setRequestMethod("GET");
        return readResponse(c);
    }

    private static String httpPut(String url, String body) throws Exception {
        var c = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        c.setRequestMethod("PUT");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (var os = c.getOutputStream()) {
            os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return readResponse(c);
    }

    private static String httpPost(String url, String body) throws Exception {
        var c = (java.net.HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (var os = c.getOutputStream()) {
            os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return readResponse(c);
    }

    private static String readResponse(java.net.HttpURLConnection c) throws Exception {
        try (var is = c.getInputStream();
             var reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}