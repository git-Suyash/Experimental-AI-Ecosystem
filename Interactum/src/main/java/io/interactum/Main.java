package io.interactum;


import io.interactum.decoder.*;
import io.interactum.ecoder.*;
import io.interactum.model.*;
import io.interactum.parser.JsonParser;
import io.interactum.util.*;
import io.interactum.validator.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * LSON Toolkit — Main Driver
 *
 * Programmatic API:
 *   LsonToolkit.encode(inputPath, outputPath)
 *   LsonToolkit.decode(inputPath, outputPath, pretty)
 *   LsonToolkit.validate(inputPath)
 *   LsonToolkit.roundtrip(inputPath)
 *
 * CLI usage (after mvn package):
 *   java -jar target/lson-toolkit-1.0.0.jar encode   input.json  output.lson
 *   java -jar target/lson-toolkit-1.0.0.jar decode   input.lson  output.json [--pretty]
 *   java -jar target/lson-toolkit-1.0.0.jar validate input.lson
 *   java -jar target/lson-toolkit-1.0.0.jar roundtrip input.json
 */
public class Main {

    // ─── ANSI colours ─────────────────────────────────────────────────────────

    private static final String RESET  = "\033[0m";
    private static final String GREEN  = "\033[32m";
    private static final String RED    = "\033[31m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN   = "\033[36m";
    private static final String BOLD   = "\033[1m";

    // =========================================================================
    // CLI Entry Point
    // =========================================================================

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        String command = args[0].toLowerCase();

        try {
            switch (command) {
                case "encode":
                    requireArgs(args, 3, "encode <input.json> <output.lson>");
                    EncodeResult er = encode(args[1], args[2]);
                    printEncodeResult(er);
                    break;

                case "decode":
                    requireArgs(args, 3, "decode <input.lson> <output.json> [--pretty]");
                    boolean pretty = args.length > 3 && args[3].equals("--pretty");
                    DecodeResult dr = decode(args[1], args[2], pretty);
                    printDecodeResult(dr);
                    break;

                case "validate":
                    requireArgs(args, 2, "validate <input.lson>");
                    InteractumValidator.ValidationResult vr = validate(args[1]);
                    printValidationResult(vr);
                    if (!vr.valid) System.exit(1);
                    break;

                case "roundtrip":
                    requireArgs(args, 2, "roundtrip <input.json>");
                    RoundtripResult rr = roundtrip(args[1]);
                    printRoundtripResult(rr);
                    if (!rr.passed) System.exit(1);
                    break;

                default:
                    err("Unknown command: " + command);
                    printUsage();
                    System.exit(1);
            }
        } catch (IOException e) {
            err("File error: " + e.getMessage());
            System.exit(1);
        } catch (JsonParseException e) {
            err("JSON parse error: " + e.getMessage());
            System.exit(1);
        } catch (InteractumDecodeException e) {
            err("LSON decode error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            err("Unexpected error: " + e.getMessage());
            System.exit(1);
        }
    }

    // =========================================================================
    // Public Programmatic API
    // =========================================================================

    // ─── Result Types ─────────────────────────────────────────────────────────

    public static class EncodeResult {
        public final String inputPath;
        public final String outputPath;
        public final long   inputBytes;
        public final long   outputBytes;
        public final int    outputLines;
        public final long   elapsedMs;
        public final double savingsPct;

        EncodeResult(String inputPath, String outputPath,
                     long inputBytes, long outputBytes,
                     int outputLines, long elapsedMs) {
            this.inputPath   = inputPath;
            this.outputPath  = outputPath;
            this.inputBytes  = inputBytes;
            this.outputBytes = outputBytes;
            this.outputLines = outputLines;
            this.elapsedMs   = elapsedMs;
            this.savingsPct  = (1.0 - (double) outputBytes / inputBytes) * 100.0;
        }
    }

    public static class DecodeResult {
        public final String  inputPath;
        public final String  outputPath;
        public final long    inputBytes;
        public final long    outputBytes;
        public final long    elapsedMs;
        public final boolean pretty;

        DecodeResult(String inputPath, String outputPath,
                     long inputBytes, long outputBytes,
                     long elapsedMs, boolean pretty) {
            this.inputPath   = inputPath;
            this.outputPath  = outputPath;
            this.inputBytes  = inputBytes;
            this.outputBytes = outputBytes;
            this.elapsedMs   = elapsedMs;
            this.pretty      = pretty;
        }
    }

    public static class RoundtripResult {
        public final String  inputPath;
        public final String  lsonPath;
        public final String  outputJsonPath;
        public final boolean passed;
        public final long    inputBytes;
        public final long    lsonBytes;
        public final double  savingsPct;
        public final int     lsonLines;
        public final String  failureReason;   // null if passed
        public final InteractumValidator.ValidationResult validation;

        RoundtripResult(String inputPath, String lsonPath, String outputJsonPath,
                        boolean passed, long inputBytes, long lsonBytes,
                        int lsonLines, String failureReason,
                        InteractumValidator.ValidationResult validation) {
            this.inputPath      = inputPath;
            this.lsonPath       = lsonPath;
            this.outputJsonPath = outputJsonPath;
            this.passed         = passed;
            this.inputBytes     = inputBytes;
            this.lsonBytes      = lsonBytes;
            this.savingsPct     = (1.0 - (double) lsonBytes / inputBytes) * 100.0;
            this.lsonLines      = lsonLines;
            this.failureReason  = failureReason;
            this.validation     = validation;
        }
    }

    // ─── encode() ─────────────────────────────────────────────────────────────

    /**
     * Encode a JSON file to LSON.
     *
     * @param inputPath  path to the source .json file
     * @param outputPath path to write the .lson output
     * @return EncodeResult with size and timing statistics
     * @throws IOException       if files cannot be read/written
     * @throws JsonParseException if the input is not valid JSON
     */
    public static EncodeResult encode(String inputPath, String outputPath)
            throws IOException {

        String json  = readFile(inputPath);
        long   t0    = System.currentTimeMillis();

        JsonValue parsed = JsonParser.parse(json);

        StringBuilder sb = new StringBuilder();
        InteractumEncoder.appendHeader(sb);
        InteractumEncoder.encodeValue(parsed, sb, 0, null);
        String lson = sb.toString();

        writeFile(outputPath, lson);

        long elapsed = System.currentTimeMillis() - t0;
        return new EncodeResult(
                inputPath, outputPath,
                json.getBytes().length,
                lson.getBytes().length,
                lson.split("\n").length,
                elapsed
        );
    }

    // ─── decode() ─────────────────────────────────────────────────────────────

    /**
     * Decode an LSON file back to JSON.
     *
     * @param inputPath  path to the source .lson file
     * @param outputPath path to write the .json output
     * @param pretty     true for indented JSON, false for compact
     * @return DecodeResult with size and timing statistics
     * @throws IOException        if files cannot be read/written
     * @throws InteractumDecodeException if the input is not valid LSON
     */
    public static DecodeResult decode(String inputPath, String outputPath, boolean pretty)
            throws IOException {

        String lson = readFile(inputPath);
        long   t0   = System.currentTimeMillis();

        JsonValue decoded = InteractumDecoder.decode(lson);
        String    json    = JsonSerializer.toJson(decoded, pretty);

        writeFile(outputPath, json);

        long elapsed = System.currentTimeMillis() - t0;
        return new DecodeResult(
                inputPath, outputPath,
                lson.getBytes().length,
                json.getBytes().length,
                elapsed, pretty
        );
    }

    // ─── validate() ───────────────────────────────────────────────────────────

    /**
     * Validate the structural correctness of an LSON file.
     *
     * @param inputPath path to the .lson file to validate
     * @return ValidationResult containing all diagnostics
     * @throws IOException if the file cannot be read
     */
    public static InteractumValidator.ValidationResult validate(String inputPath)
            throws IOException {

        String lson = readFile(inputPath);
        return InteractumValidator.validate(lson);
    }

    // ─── roundtrip() ──────────────────────────────────────────────────────────

    /**
     * Full round-trip test: encode → validate → decode → compare.
     * Writes intermediate .lson and final .json to the same directory
     * as the input file, with suffixes .out.lson and .roundtrip.json.
     *
     * @param inputPath path to the source .json file
     * @return RoundtripResult with pass/fail and all diagnostics
     * @throws IOException        if files cannot be read/written
     * @throws JsonParseException if the input is not valid JSON
     */
    public static RoundtripResult roundtrip(String inputPath) throws IOException {

        String base         = stripExtension(inputPath);
        String lsonPath     = base + ".out.lson";
        String outJsonPath  = base + ".roundtrip.json";

        // Step 1: Parse
        String    originalJson = readFile(inputPath);
        JsonValue original     = JsonParser.parse(originalJson);

        // Step 2: Encode
        StringBuilder lsonSb = new StringBuilder();
        InteractumEncoder.appendHeader(lsonSb);
        InteractumEncoder.encodeValue(original, lsonSb, 0, null);
        String lson = lsonSb.toString();
        writeFile(lsonPath, lson);

        // Step 3: Validate
        InteractumValidator.ValidationResult vr = InteractumValidator.validate(lson);
        if (!vr.valid) {
            return new RoundtripResult(
                    inputPath, lsonPath, outJsonPath,
                    false,
                    originalJson.getBytes().length,
                    lson.getBytes().length,
                    lson.split("\n").length,
                    "Validation failed: " + vr.errorCount() + " error(s)",
                    vr
            );
        }

        // Step 4: Decode
        JsonValue decoded;
        try {
            decoded = InteractumDecoder.decode(lson);
        } catch (InteractumDecodeException e) {
            return new RoundtripResult(
                    inputPath, lsonPath, outJsonPath,
                    false,
                    originalJson.getBytes().length,
                    lson.getBytes().length,
                    lson.split("\n").length,
                    "Decode failed: " + e.getMessage(),
                    vr
            );
        }

        // Step 5: Compare canonical JSON
        String canonicalOriginal = JsonSerializer.toJson(original, false);
        String canonicalDecoded  = JsonSerializer.toJson(decoded,  false);
        writeFile(outJsonPath, JsonSerializer.toJson(decoded, true));

        boolean passed        = canonicalOriginal.equals(canonicalDecoded);
        String  failureReason = null;

        if (!passed) {
            int diverge = firstDivergence(canonicalOriginal, canonicalDecoded);
            failureReason = "Round-trip mismatch at char " + diverge +
                    ": ..." + safeSubstr(canonicalOriginal, diverge, 60) + "...";
        }

        return new RoundtripResult(
                inputPath, lsonPath, outJsonPath,
                passed,
                originalJson.getBytes().length,
                lson.getBytes().length,
                lson.split("\n").length,
                failureReason,
                vr
        );
    }

    // =========================================================================
    // Console Output Helpers
    // =========================================================================

    private static void printEncodeResult(EncodeResult r) {
        banner("ENCODE", r.inputPath + " → " + r.outputPath);
        ok("Encode successful");
        out("  Input:   " + fmtSize(r.inputBytes)  + "  (" + r.inputPath  + ")");
        out("  Output:  " + fmtSize(r.outputBytes) + "  (" + r.outputPath + ")");
        out(String.format("  Savings: %.1f%%  |  Lines: %d  |  Time: %dms",
                r.savingsPct, r.outputLines, r.elapsedMs));
    }

    private static void printDecodeResult(DecodeResult r) {
        banner("DECODE", r.inputPath + " → " + r.outputPath);
        ok("Decode successful");
        out("  Input:   " + fmtSize(r.inputBytes)  + "  (" + r.inputPath + ")");
        out("  Output:  " + fmtSize(r.outputBytes) + "  (" + r.outputPath + ")"
                + (r.pretty ? "  [pretty]" : "  [compact]"));
        out("  Time: " + r.elapsedMs + "ms");
    }

    private static void printValidationResult(InteractumValidator.ValidationResult vr) {
        if (vr.diagnostics.isEmpty()) {
            ok("No issues found");
            return;
        }
        for (InteractumValidator.Diagnostic d : vr.diagnostics) {
            String color = d.severity == InteractumValidator.Severity.ERROR   ? RED
                    : d.severity == InteractumValidator.Severity.WARNING ? YELLOW
                    : CYAN;
            System.out.println(color + d + RESET);
        }
        out("");
        out("  Errors:   " + vr.errorCount());
        out("  Warnings: " + vr.warnCount());
        if (vr.valid) ok("Validation passed");
        else          fail("Validation failed");
    }

    private static void printRoundtripResult(RoundtripResult r) {
        banner("ROUNDTRIP", r.inputPath);

        step("Step 1: Parse JSON");
        step("Step 2: Encode → " + r.lsonPath);
        step("Step 3: Validate LSON");

        if (r.validation != null) {
            if (r.validation.valid) {
                ok("  Valid" + (r.validation.warnCount() > 0
                        ? " (" + r.validation.warnCount() + " warning(s))" : ""));
            } else {
                fail("  Validation errors:");
                for (InteractumValidator.Diagnostic d : r.validation.diagnostics)
                    if (d.severity == InteractumValidator.Severity.ERROR)
                        out("    " + d);
            }
        }

        step("Step 4: Decode LSON");
        step("Step 5: Compare");

        if (r.passed) {
            ok("  Round-trip fidelity confirmed");
            out("");
            out(BOLD + "Summary:" + RESET);
            out("  Original JSON : " + fmtSize(r.inputBytes));
            out(String.format("  LSON          : %s  (%.1f%% savings)",
                    fmtSize(r.lsonBytes), r.savingsPct));
            out("  LSON lines    : " + r.lsonLines);
            ok("\nFull round-trip passed");
            out("  Written: " + r.lsonPath);
            out("  Written: " + r.outputJsonPath);
        } else {
            fail("  " + r.failureReason);
            fail("\nRound-trip FAILED");
        }
    }

    // =========================================================================
    // Internal Utilities
    // =========================================================================

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)));
    }

    private static void writeFile(String path, String content) throws IOException {
        Files.write(Paths.get(path), content.getBytes());
    }

    private static String fmtSize(long bytes) {
        if (bytes < 1024)            return bytes + " B";
        if (bytes < 1024 * 1024)     return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024));
    }

    private static String stripExtension(String path) {
        int dot = path.lastIndexOf('.');
        return dot > 0 ? path.substring(0, dot) : path;
    }

    private static int firstDivergence(String a, String b) {
        int len = Math.min(a.length(), b.length());
        for (int i = 0; i < len; i++)
            if (a.charAt(i) != b.charAt(i)) return i;
        return len;
    }

    private static String safeSubstr(String s, int from, int len) {
        int start = Math.max(0, from - 20);
        int end   = Math.min(s.length(), from + len);
        return s.substring(start, end);
    }

    private static void requireArgs(String[] args, int min, String usage) {
        if (args.length < min) {
            err("Usage: java -jar lson-toolkit.jar " + usage);
            System.exit(1);
        }
    }

    private static void printUsage() {
        out(BOLD + "\nLSON Toolkit v1.0.0" + RESET);
        out("");
        out("Commands:");
        out("  " + CYAN + "encode   " + RESET + "<input.json>  <output.lson>");
        out("  " + CYAN + "decode   " + RESET + "<input.lson>  <output.json>  [--pretty]");
        out("  " + CYAN + "validate " + RESET + "<input.lson>");
        out("  " + CYAN + "roundtrip" + RESET + " <input.json>");
        out("");
        out("Programmatic API:");
        out("  Main.encode(inputPath, outputPath)");
        out("  Main.decode(inputPath, outputPath, pretty)");
        out("  Main.validate(inputPath)");
        out("  Main.roundtrip(inputPath)");
    }

    private static void banner(String cmd, String detail) {
        out(BOLD + CYAN + "\n┌─ LSON " + cmd + " " + "─".repeat(Math.max(0, 36 - cmd.length())) + "┐" + RESET);
        out(BOLD + CYAN + "│ " + RESET + detail);
        out(BOLD + CYAN + "└" + "─".repeat(44) + "┘" + RESET);
    }

    private static void out(String s)  { System.out.println(s); }
    private static void err(String s)  { System.err.println(RED + s + RESET); }
    private static void ok(String s)   { System.out.println(GREEN  + s + RESET); }
    private static void fail(String s) { System.out.println(RED    + s + RESET); }
    private static void step(String s) { System.out.println(CYAN   + s + RESET); }
}
