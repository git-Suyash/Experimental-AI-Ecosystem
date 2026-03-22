package io.interactum.ecoder;

import io.interactum.model.*;
import io.interactum.parser.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.math.BigDecimal;

public class InteractumEncoder {


    /**
     * LSON Encoder — converts JSON to Line-Structured Object Notation (LSON)
     *
     * LSON Design Principles:
     *  - Optional [N] length declarations (omitted = streaming-friendly)
     *  - Explicit tabular form via {field, field} header
     *  - Inline JSON fallback for deeply nested structures (> 2 levels)
     *  - Terminal markers (---) to close array blocks
     *  - Single quoting rule: quote if value could be misread as structure or primitive type
     *  - Comments supported (# to end of line)
     *  - Type hints in tabular headers (optional)
     *  - Two structural levels + inline JSON for the rest
     */


        // ─── Configuration ────────────────────────────────────────────────────────

        static final String VERSION        = "1.0";
        static final String INDENT         = "  ";          // 2 spaces per level
        static final int    MAX_LSON_DEPTH = 2;             // beyond this → inline JSON
        static final String BLOCK_CLOSE    = "---";

        /**
         * Regex: a string MUST be quoted if it matches.
         * Conditions: empty, boolean/null literals, numeric-like,
         * or contains structural characters, or starts with hyphen.
         */
        static final Pattern MUST_QUOTE = Pattern.compile(
                "^$" +                                    // empty string
                        "|^(true|false|null)$" +                  // boolean / null literals
                        "|^-?[\\d]" +                             // numeric-like (starts with digit or minus-digit)
                        "|^-" +                                   // starts with hyphen (list item marker risk)
                        "|[:\\,\\|\\t\\[\\]\\{\\}\"\\\\]" +       // structural characters
                        "|[\\r\\n]"                               // newlines
        );

        // ─── Entry Point ──────────────────────────────────────────────────────────

        public static void main(String[] args) {
            if (args.length < 2) {
                System.err.println("Usage: java LsonEncoder <input.json> <output.lson>");
                System.exit(1);
            }

            String inputPath  = args[0];
            String outputPath = args[1];

            try {
                String jsonContent = new String(Files.readAllBytes(Paths.get(inputPath)));
                System.out.println("Reading: " + inputPath);

                JsonValue parsed = JsonParser.parse(jsonContent);
                System.out.println("Parsed JSON successfully.");

                StringBuilder sb = new StringBuilder();
                appendHeader(sb);
                encodeValue(parsed, sb, 0, null);

                Files.write(Paths.get(outputPath), sb.toString().getBytes());
                System.out.println("Written: " + outputPath);
                System.out.println("Done.");

            } catch (IOException e) {
                System.err.println("File error: " + e.getMessage());
                System.exit(1);
            } catch (JsonParseException e) {
                System.err.println("JSON parse error: " + e.getMessage());
                System.exit(1);
            }
        }

        // ─── Document Header ──────────────────────────────────────────────────────

        public static void appendHeader(StringBuilder sb) {
            sb.append("%lson ").append(VERSION).append("\n");
            sb.append("%delimiter comma\n");
            sb.append("%strict true\n");
            sb.append(BLOCK_CLOSE).append("\n");
        }

        // ─── Core Encoder ─────────────────────────────────────────────────────────

        /**
         * Encode any JsonValue.
         *
         * @param value   the value to encode
         * @param sb      output buffer
         * @param depth   current indentation depth (0 = root)
         * @param key     the key this value is associated with (null for root/array items)
         */
        public static void encodeValue(JsonValue value, StringBuilder sb, int depth, String key) {
            if (value instanceof JsonPrimitive) {
                encodePrimitive((JsonPrimitive) value, sb, depth, key);

            } else if (value instanceof JsonObject) {
                encodeObject((JsonObject) value, sb, depth, key);

            } else if (value instanceof JsonArray) {
                encodeArray((JsonArray) value, sb, depth, key);
            }
        }

        // ─── Primitive ────────────────────────────────────────────────────────────

        static void encodePrimitive(JsonPrimitive prim, StringBuilder sb, int depth, String key) {
            String encoded = encodePrimitiveValue(prim);
            if (key != null) {
                sb.append(indent(depth)).append(encodeKey(key)).append(": ").append(encoded).append("\n");
            } else {
                sb.append(encoded);
            }
        }

        /**
         * Encode a primitive value to its LSON string representation.
         * Applies quoting rules and number normalization.
         */
        static String encodePrimitiveValue(JsonPrimitive prim) {
            if (prim.value == null)              return "null";
            if (prim.value instanceof Boolean)   return prim.value.toString();
            if (prim.value instanceof Number)    return normalizeNumber((Number) prim.value);

            // It's a string
            String s = (String) prim.value;
            if (mustQuote(s)) {
                return "\"" + escapeString(s) + "\"";
            }
            return s;
        }

        // ─── Object ───────────────────────────────────────────────────────────────

        static void encodeObject(JsonObject obj, StringBuilder sb, int depth, String key) {
            if (obj.fields.isEmpty()) {
                if (key != null) {
                    sb.append(indent(depth)).append(encodeKey(key)).append(": {}\n");
                }
                return;
            }

            // If we're beyond max LSON depth, fall back to inline JSON
            if (depth >= MAX_LSON_DEPTH) {
                String json = toInlineJson(new JsonObject(obj.fields));
                if (key != null) {
                    sb.append(indent(depth)).append(encodeKey(key)).append(": ").append(json).append("\n");
                } else {
                    sb.append(json);
                }
                return;
            }

            // Normal object encoding
            if (key != null) {
                sb.append(indent(depth)).append(encodeKey(key)).append(":\n");
            }

            int childDepth = (key != null) ? depth + 1 : depth;
            for (Map.Entry<String, JsonValue> entry : obj.fields.entrySet()) {
                encodeValue(entry.getValue(), sb, childDepth, entry.getKey());
            }
        }

        // ─── Array ────────────────────────────────────────────────────────────────

        static void encodeArray(JsonArray arr, StringBuilder sb, int depth, String key) {
            String keyPart = (key != null) ? encodeKey(key) : "";
            String linePrefix = indent(depth) + (key != null ? keyPart : "");
            // Empty array
            if (arr.elements.isEmpty()) {
                sb.append(indent(depth)).append(keyPart).append("[0]:\n");
                sb.append(indent(depth)).append(BLOCK_CLOSE).append("\n");
                return;
            }

            // Detect if all elements are primitives → inline primitive array
            if (allPrimitives(arr.elements)) {
                encodePrimitiveArray(arr, sb, depth, keyPart);
                return;
            }

            // Detect if all elements are uniform objects with primitive-only values → tabular
            List<String> tabularFields = detectTabularFields(arr.elements);
            if (tabularFields != null && depth < MAX_LSON_DEPTH) {
                encodeTabularArray(arr, tabularFields, sb, depth, keyPart);
                return;
            }

            // Mixed / non-uniform or deeply nested → expanded list
            encodeExpandedArray(arr, sb, depth, keyPart, key);
        }

        // Inline primitive array: tags[3]: a, b, c
        static void encodePrimitiveArray(JsonArray arr, StringBuilder sb, int depth, String keyPart) {
            List<String> encoded = new ArrayList<>();
            for (JsonValue v : arr.elements) {
                encoded.add(encodePrimitiveValue((JsonPrimitive) v));
            }
            int n = arr.elements.size();
            sb.append(indent(depth)).append(keyPart).append("[").append(n).append("]: ")
                    .append(String.join(", ", encoded)).append("\n");
        }

        // Tabular array: users[]{id, name, role}:
        static void encodeTabularArray(JsonArray arr, List<String> fields,
                                       StringBuilder sb, int depth, String keyPart) {
            int n = arr.elements.size();
            String fieldList = buildFieldList(fields);

            sb.append(indent(depth))
                    .append(keyPart).append("[").append(n).append("]")
                    .append("{").append(fieldList).append("}:\n");

            for (JsonValue elem : arr.elements) {
                JsonObject obj = (JsonObject) elem;
                List<String> rowVals = new ArrayList<>();
                for (String f : fields) {
                    JsonValue v = obj.fields.getOrDefault(f, JsonPrimitive.NULL);
                    rowVals.add(encodePrimitiveValue((JsonPrimitive) v));
                }
                sb.append(indent(depth + 1)).append(String.join(", ", rowVals)).append("\n");
            }

            sb.append(indent(depth)).append(BLOCK_CLOSE).append("\n");
        }

        // Expanded list: mixed/non-uniform/deeply nested
        static void encodeExpandedArray(JsonArray arr, StringBuilder sb,
                                        int depth, String keyPart, String key) {
            int n = arr.elements.size();
            sb.append(indent(depth)).append(keyPart).append("[").append(n).append("]:\n");

            for (JsonValue elem : arr.elements) {
                if (elem instanceof JsonPrimitive) {
                    sb.append(indent(depth + 1)).append("- ")
                            .append(encodePrimitiveValue((JsonPrimitive) elem)).append("\n");

                } else if (elem instanceof JsonArray) {
                    JsonArray inner = (JsonArray) elem;
                    if (allPrimitives(inner.elements)) {
                        List<String> vals = new ArrayList<>();
                        for (JsonValue v : inner.elements) vals.add(encodePrimitiveValue((JsonPrimitive) v));
                        sb.append(indent(depth + 1))
                                .append("- [").append(inner.elements.size()).append("]: ")
                                .append(String.join(", ", vals)).append("\n");
                    } else {
                        // Nested complex array → inline JSON
                        sb.append(indent(depth + 1)).append("- ").append(toInlineJson(elem)).append("\n");
                    }

                } else if (elem instanceof JsonObject) {
                    JsonObject obj = (JsonObject) elem;
                    if (obj.fields.isEmpty()) {
                        sb.append(indent(depth + 1)).append("-\n");
                    } else {
                        // Check if first field is a tabular array
                        Map.Entry<String, JsonValue> firstEntry = obj.fields.entrySet().iterator().next();
                        boolean firstIsTabular = false;
                        List<String> tabFields = null;

                        if (firstEntry.getValue() instanceof JsonArray && depth + 1 < MAX_LSON_DEPTH) {
                            JsonArray fa = (JsonArray) firstEntry.getValue();
                            tabFields = detectTabularFields(fa.elements);
                            firstIsTabular = tabFields != null;
                        }

                        if (firstIsTabular) {
                            // Emit tabular header on hyphen line
                            JsonArray fa = (JsonArray) firstEntry.getValue();
                            String fKeyPart = encodeKey(firstEntry.getKey());
                            String fieldList = buildFieldList(tabFields);
                            sb.append(indent(depth + 1))
                                    .append("- ").append(fKeyPart)
                                    .append("[").append(fa.elements.size()).append("]")
                                    .append("{").append(fieldList).append("}:\n");

                            for (JsonValue row : fa.elements) {
                                JsonObject rowObj = (JsonObject) row;
                                List<String> rowVals = new ArrayList<>();
                                for (String f : tabFields) {
                                    JsonValue v = rowObj.fields.getOrDefault(f, JsonPrimitive.NULL);
                                    rowVals.add(encodePrimitiveValue((JsonPrimitive) v));
                                }
                                sb.append(indent(depth + 3)).append(String.join(", ", rowVals)).append("\n");
                            }
                            sb.append(indent(depth + 2)).append(BLOCK_CLOSE).append("\n");

                            // Remaining fields at depth+2
                            boolean first = true;
                            for (Map.Entry<String, JsonValue> e : obj.fields.entrySet()) {
                                if (first) { first = false; continue; } // skip first field
                                encodeValue(e.getValue(), sb, depth + 2, e.getKey());
                            }

                        } else {
                            // Regular object as list item: first field on hyphen line
                            Iterator<Map.Entry<String, JsonValue>> it = obj.fields.entrySet().iterator();
                            Map.Entry<String, JsonValue> first2 = it.next();

                            if (first2.getValue() instanceof JsonPrimitive) {
                                sb.append(indent(depth + 1))
                                        .append("- ").append(encodeKey(first2.getKey())).append(": ")
                                        .append(encodePrimitiveValue((JsonPrimitive) first2.getValue())).append("\n");
                            } else {
                                // Non-primitive first field → use inline JSON for whole object if deep
                                if (depth + 1 >= MAX_LSON_DEPTH) {
                                    sb.append(indent(depth + 1)).append("- ").append(toInlineJson(elem)).append("\n");
                                    continue;
                                }
                                sb.append(indent(depth + 1))
                                        .append("- ").append(encodeKey(first2.getKey())).append(":\n");
                                encodeValue(first2.getValue(), sb, depth + 3, null);
                            }

                            // Remaining fields at depth+2
                            while (it.hasNext()) {
                                Map.Entry<String, JsonValue> e = it.next();
                                encodeValue(e.getValue(), sb, depth + 2, e.getKey());
                            }
                        }
                    }
                }
            }

            sb.append(indent(depth)).append(BLOCK_CLOSE).append("\n");
        }

        // ─── Tabular Detection ────────────────────────────────────────────────────

        /**
         * Returns ordered field list if array qualifies for tabular encoding:
         *  - All elements are objects
         *  - All objects have the same key set
         *  - All values are primitives
         * Returns null otherwise.
         */
        static List<String> detectTabularFields(List<JsonValue> elements) {
            if (elements.isEmpty()) return null;

            // All must be objects
            for (JsonValue v : elements) {
                if (!(v instanceof JsonObject)) return null;
            }

            JsonObject first = (JsonObject) elements.get(0);
            List<String> fields = new ArrayList<>(first.fields.keySet());

            for (JsonValue v : elements) {
                JsonObject obj = (JsonObject) v;
                if (!obj.fields.keySet().equals(first.fields.keySet())) return null;
                for (JsonValue fv : obj.fields.values()) {
                    if (!(fv instanceof JsonPrimitive)) return null;
                }
            }

            return fields;
        }

        // ─── Helpers ──────────────────────────────────────────────────────────────

        static boolean allPrimitives(List<JsonValue> elements) {
            for (JsonValue v : elements) if (!(v instanceof JsonPrimitive)) return false;
            return true;
        }

        static String buildFieldList(List<String> fields) {
            List<String> encoded = new ArrayList<>();
            for (String f : fields) encoded.add(encodeKey(f));
            return String.join(", ", encoded);
        }

        static String indent(int depth) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < depth; i++) sb.append(INDENT);
            return sb.toString();
        }

        static String encodeKey(String key) {
            // Keys may be unquoted if they match: ^[A-Za-z_][A-Za-z0-9_.]*$
            if (key.matches("^[A-Za-z_][A-Za-z0-9_.]*$")) return key;
            return "\"" + escapeString(key) + "\"";
        }

        static boolean mustQuote(String s) {
            return MUST_QUOTE.matcher(s).find();
        }

        public static String escapeString(String s) {
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }

        /**
         * Canonical number normalization:
         *  - No trailing zeros in fractional part
         *  - No exponent notation
         *  - -0 → 0
         *
         * Uses Double.toString() which gives the shortest round-trip representation,
         * then strips trailing zeros and converts to plain decimal (no exponent).
         */
        public static String normalizeNumber(Number n) {
            if (n instanceof Double || n instanceof Float) {
                double d = n.doubleValue();
                if (d == 0.0) return "0";
                if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
                // Double.toString gives shortest round-trip string (e.g. "9.5", "29.99")
                // Then strip any exponent via BigDecimal for plain form
                String dStr = Double.toString(d);
                BigDecimal bd = new BigDecimal(dStr).stripTrailingZeros();
                String plain = bd.toPlainString();
                // If no fractional part remains, emit as integer
                if (!plain.contains(".")) return plain;
                return plain;
            }
            if (n instanceof Long || n instanceof Integer) {
                long l = n.longValue();
                return Long.toString(l);
            }
            // BigDecimal / BigInteger etc.
            return n.toString();
        }

        /**
         * Serialize any JsonValue back to compact inline JSON.
         * Used as fallback for depth > MAX_LSON_DEPTH.
         */
        static String toInlineJson(JsonValue value) {
            if (value instanceof JsonPrimitive) {
                JsonPrimitive p = (JsonPrimitive) value;
                if (p.value == null) return "null";
                if (p.value instanceof Boolean) return p.value.toString();
                if (p.value instanceof Number) return normalizeNumber((Number) p.value);
                return "\"" + escapeString((String) p.value) + "\"";
            }
            if (value instanceof JsonObject) {
                JsonObject obj = (JsonObject) value;
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<String, JsonValue> e : obj.fields.entrySet()) {
                    if (!first) sb.append(", ");
                    sb.append("\"").append(escapeString(e.getKey())).append("\": ");
                    sb.append(toInlineJson(e.getValue()));
                    first = false;
                }
                sb.append("}");
                return sb.toString();
            }
            if (value instanceof JsonArray) {
                JsonArray arr = (JsonArray) value;
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < arr.elements.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(toInlineJson(arr.elements.get(i)));
                }
                sb.append("]");
                return sb.toString();
            }
            return "null";
        }

}
