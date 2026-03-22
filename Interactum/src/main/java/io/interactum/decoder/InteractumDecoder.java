package io.interactum.decoder;

import io.interactum.model.*;
import io.interactum.parser.JsonParser;

import java.util.*;
import java.util.regex.*;

/**
 * LSON Decoder — converts LSON text back to the JSON data model.
 *
 * Handles:
 *  - Document headers (%lson, %delimiter, %strict directives)
 *  - Flat key-value objects
 *  - Nested objects (indentation-based)
 *  - Primitive inline arrays: key[N]: v1, v2
 *  - Tabular arrays: key[N]{f1, f2}: with rows
 *  - Expanded list arrays: key[N]: with - items
 *  - Arrays of arrays: - [M]: v1, v2
 *  - Objects as list items (first field on hyphen line)
 *  - Inline JSON fallback values
 *  - Terminal block markers (---)
 *  - Comment lines (# ...)
 *  - Optional [N] length validation in strict mode
 */
public class InteractumDecoder {

    // ─── Document Options ─────────────────────────────────────────────────────

    static final int    DEFAULT_INDENT = 2;
    static final String BLOCK_CLOSE    = "---";

    // ─── Line Representation ──────────────────────────────────────────────────

    static class Line {
        final int    lineNo;
        final int    depth;
        final String raw;       // original trimmed content
        final String content;   // raw minus leading spaces

        Line(int lineNo, int depth, String raw, String content) {
            this.lineNo  = lineNo;
            this.depth   = depth;
            this.raw     = raw;
            this.content = content;
        }

        boolean isComment()    { return content.startsWith("#"); }
        boolean isDirective()  { return content.startsWith("%"); }
        boolean isBlockClose() { return content.equals(BLOCK_CLOSE); }
        boolean isListItem()   { return content.startsWith("- ") || content.equals("-"); }

        @Override public String toString() {
            return "[L" + lineNo + " d" + depth + "] " + content;
        }
    }

    // ─── Decoder State ────────────────────────────────────────────────────────

    private final List<Line> lines;
    private int              cursor;
    private int              indentSize;
    private boolean          strict;
    private char             delimiter;

    private InteractumDecoder(List<Line> lines, int indentSize, boolean strict, char delimiter) {
        this.lines      = lines;
        this.cursor     = 0;
        this.indentSize = indentSize;
        this.strict     = strict;
        this.delimiter  = delimiter;
    }

    // ─── Public Entry Point ───────────────────────────────────────────────────

    public static JsonValue decode(String input) {
        return decode(input, DEFAULT_INDENT, true, ',');
    }

    static JsonValue decode(String input, int indentSize, boolean strict, char delimiter) {
        // Phase 1: split and tokenize lines
        String[] rawLines = input.split("\n", -1);

        // Phase 2: parse directives from header
        int[]    opts      = { indentSize };
        boolean[]  strictArr  = { strict };
        char[]   delimArr  = { delimiter };
        int      startLine = parseDirectives(rawLines, opts, strictArr, delimArr);

        // Phase 3: build Line objects
        List<Line> lines = buildLines(rawLines, startLine, opts[0], strictArr[0]);

        // Phase 4: decode
        InteractumDecoder decoder = new InteractumDecoder(lines, opts[0], strictArr[0], delimArr[0]);
        return decoder.decodeRoot();
    }

    // ─── Directive Parsing ────────────────────────────────────────────────────

    /**
     * Scan for %lson, %delimiter, %strict directives before the first --- separator.
     * Returns the line index to start actual content parsing from.
     */
    static int parseDirectives(String[] rawLines, int[] indentOut,
                               boolean[] strictOut, char[] delimOut) {
        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i].trim();
            if (line.equals(BLOCK_CLOSE)) return i + 1; // content starts after ---
            if (line.startsWith("%lson"))      { /* version noted, no action */ }
            else if (line.startsWith("%delimiter ")) {
                String d = line.substring("%delimiter ".length()).trim();
                if (d.equals("tab"))   delimOut[0] = '\t';
                else if (d.equals("pipe")) delimOut[0] = '|';
                else                   delimOut[0] = ',';
            } else if (line.startsWith("%strict ")) {
                String s = line.substring("%strict ".length()).trim();
                strictOut[0] = !s.equals("false");
            }
        }
        return 0; // no --- found, start from beginning
    }

    // ─── Line Building ────────────────────────────────────────────────────────

    static List<Line> buildLines(String[] rawLines, int startIdx,
                                 int indentSize, boolean strict) {
        List<Line> result = new ArrayList<>();
        for (int i = startIdx; i < rawLines.length; i++) {
            String raw = rawLines[i];

            // Count leading spaces
            int spaces = 0;
            while (spaces < raw.length() && raw.charAt(spaces) == ' ') spaces++;

            String content = raw.substring(spaces);

            // Skip blank lines and comment lines at parse stage
            if (content.isEmpty()) continue;
            if (content.startsWith("#")) continue;

            // Validate indentation in strict mode
            if (strict && spaces % indentSize != 0) {
                throw new InteractumDecodeException(
                        "Line " + (i + 1) + ": indentation " + spaces +
                                " is not a multiple of " + indentSize
                );
            }

            int depth = spaces / indentSize;
            result.add(new Line(i + 1, depth, raw, content));
        }
        return result;
    }

    // ─── Root Decoder ─────────────────────────────────────────────────────────

    JsonValue decodeRoot() {
        if (lines.isEmpty()) return new JsonObject();

        Line first = peek();

        // Root array header? e.g. [3]: or [3]{f1,f2}:
        if (first.depth == 0 && isArrayHeader(first.content, null)) {
            return decodeArrayFromHeader(0);
        }

        // Single primitive?
        if (lines.size() == 1 && !first.content.contains(":") && !first.isListItem()) {
            consume();
            return parsePrimitiveToken(first.content.trim());
        }

        // Otherwise it's a root object
        return decodeObject(0);
    }

    // ─── Object Decoder ───────────────────────────────────────────────────────

    JsonObject decodeObject(int depth) {
        JsonObject obj = new JsonObject();

        while (cursor < lines.size()) {
            Line line = peek();

            // Stop when we hit a shallower depth
            if (line.depth < depth) break;
            if (line.depth != depth) break;

            // Block close (---): array block terminator
            // At depth > 0 it ends this object scope; at depth 0 it just closes
            // an array block — consume it and keep processing root object fields
            if (line.isBlockClose()) {
                consume();
                if (depth > 0) break;
                continue; // depth == 0: array block closed, keep reading root fields
            }

            // List items belong to arrays, not objects
            if (line.isListItem()) break;

            // Parse key — must find unquoted colon
            String content  = line.content;
            int    colonIdx = findUnquotedColon(content);
            if (colonIdx < 0) {
                // Could be a tabular row that leaked — in strict mode error, else skip
                if (strict) throw new InteractumDecodeException(
                        "Line " + line.lineNo + ": missing colon in key-value: " + content
                );
                consume(); continue;
            }

            String keyRaw   = content.substring(0, colonIdx).trim();
            String valueRaw = content.substring(colonIdx + 1).trim();
            String key      = decodeKey(keyRaw, line.lineNo);

            consume(); // consume key line

            // Is the FULL line an array header (key[N]{fields}: or key[N]:)?
            if (isArrayHeader(content, null)) {
                // Pass the full content as header — key is embedded in it
                ArrayHeader ah = parseArrayHeader(content, line.lineNo);
                String actualKey = ah.key != null ? ah.key : decodeKey(keyRaw, line.lineNo);
                JsonArray arr = decodeArrayBody(content, depth + 1, line.lineNo);
                obj.fields.put(actualKey, arr);
                continue;
            }

            // Empty value → nested object
            if (valueRaw.isEmpty()) {
                // peek ahead: is next line deeper?
                if (cursor < lines.size() && peek().depth > depth) {
                    obj.fields.put(key, decodeObject(depth + 1));
                } else {
                    obj.fields.put(key, new JsonObject()); // empty object
                }
                continue;
            }

            // Inline JSON fallback (starts with { or [)
            if (valueRaw.startsWith("{") || valueRaw.startsWith("[")) {
                try {
                    obj.fields.put(key, JsonParser.parse(valueRaw));
                    continue;
                } catch (JsonParseException e) {
                    // fall through to string
                }
            }

            // Primitive value
            obj.fields.put(key, parsePrimitiveToken(valueRaw));
        }

        return obj;
    }

    // ─── Array Decoder ────────────────────────────────────────────────────────

    /**
     * Decode an array starting from a header line already consumed by caller.
     * Used when the header is at root level.
     */
    JsonArray decodeArrayFromHeader(int depth) {
        Line headerLine = consume();
        return decodeArrayBody(headerLine.content, depth + 1, headerLine.lineNo);
    }

    /**
     * Parse array body given the header string (everything after the key, including brackets).
     * bodyDepth = depth at which rows/items appear.
     */
    JsonArray decodeArrayBody(String header, int bodyDepth, int headerLineNo) {
        // Parse header: [N]{fields}: or [N]: inline...
        ArrayHeader ah = parseArrayHeader(header, headerLineNo);

        // Inline primitive array: [3]: v1, v2, v3
        if (ah.inlineValues != null) {
            return decodeInlinePrimitiveArray(ah, headerLineNo);
        }

        // Tabular array: has field list
        if (ah.fields != null) {
            return decodeTabularArray(ah, bodyDepth, headerLineNo);
        }

        // Expanded list array
        return decodeExpandedListArray(ah, bodyDepth, headerLineNo);
    }

    // Inline primitive array
    JsonArray decodeInlinePrimitiveArray(ArrayHeader ah, int lineNo) {
        JsonArray arr = new JsonArray();
        if (ah.inlineValues.isEmpty()) {
            if (strict && ah.declaredLength >= 0 && ah.declaredLength != 0) {
                throw new InteractumDecodeException(
                        "Line " + lineNo + ": declared length " + ah.declaredLength +
                                " but got 0 values"
                );
            }
            return arr;
        }

        List<String> tokens = splitDelimited(ah.inlineValues, ah.delimiter);
        for (String t : tokens) arr.elements.add(parsePrimitiveToken(t.trim()));

        if (strict && ah.declaredLength >= 0 && arr.elements.size() != ah.declaredLength) {
            throw new InteractumDecodeException(
                    "Line " + lineNo + ": declared length " + ah.declaredLength +
                            " but got " + arr.elements.size() + " values"
            );
        }
        return arr;
    }

    // Tabular array
    JsonArray decodeTabularArray(ArrayHeader ah, int bodyDepth, int headerLineNo) {
        JsonArray arr    = new JsonArray();
        int       rowsRead = 0;

        while (cursor < lines.size()) {
            Line line = peek();
            if (line.isBlockClose()) {
                if (line.depth == bodyDepth - 1) { consume(); } // our --- , consume it
                break; // either way stop reading rows
            } // leave --- for parent (decodeObject) to consume
            if (line.depth < bodyDepth) break;
            if (line.depth != bodyDepth) break;

            // Tabular row disambiguation (same as encoder rules):
            // if unquoted delimiter comes before unquoted colon → it's a row
            // if unquoted colon comes first → it's a key-value (end of rows)
            if (!isTabularRow(line.content, ah.delimiter)) break;

            consume();
            List<String> vals = splitDelimited(line.content, ah.delimiter);

            if (strict && vals.size() != ah.fields.size()) {
                throw new InteractumDecodeException(
                        "Line " + line.lineNo + ": expected " + ah.fields.size() +
                                " columns but got " + vals.size()
                );
            }

            JsonObject row = new JsonObject();
            for (int i = 0; i < ah.fields.size(); i++) {
                String val = (i < vals.size()) ? vals.get(i).trim() : "";
                row.fields.put(ah.fields.get(i), parsePrimitiveToken(val));
            }
            arr.elements.add(row);
            rowsRead++;
        }

        if (strict && ah.declaredLength >= 0 && rowsRead != ah.declaredLength) {
            throw new InteractumDecodeException(
                    "After line " + headerLineNo + ": declared " + ah.declaredLength +
                            " rows but got " + rowsRead
            );
        }
        return arr;
    }

    // Expanded list array
    JsonArray decodeExpandedListArray(ArrayHeader ah, int bodyDepth, int headerLineNo) {
        JsonArray arr       = new JsonArray();
        int       itemsRead = 0;

        while (cursor < lines.size()) {
            Line line = peek();
            if (line.isBlockClose()) {
                if (line.depth == bodyDepth - 1) { consume(); } // our --- , consume it
                break;
            } // leave --- for parent to consume
            if (line.depth < bodyDepth) break;
            if (line.depth != bodyDepth) break;
            if (!line.isListItem()) break;

            consume();
            itemsRead++;

            String after = line.content.equals("-") ? "" :
                    line.content.substring(2); // strip "- "

            // Empty object: bare "-"
            if (after.isEmpty()) {
                arr.elements.add(new JsonObject());
                continue;
            }

            // Inner array: - [M]: v1, v2
            if (after.startsWith("[")) {
                JsonArray inner = decodeArrayBody(after, bodyDepth + 1, line.lineNo);
                arr.elements.add(inner);
                continue;
            }

            // Inline JSON fallback: - {"key": "val"}
            if (after.startsWith("{") || after.startsWith("\"")) {
                try {
                    arr.elements.add(JsonParser.parse(after));
                    continue;
                } catch (JsonParseException e) { /* fall through */ }
            }

            // Object with first field on hyphen line: - key: val or - key[N]{f}:
            int colonIdx = findUnquotedColon(after);
            if (colonIdx >= 0) {
                String keyRaw   = after.substring(0, colonIdx).trim();
                String valueRaw = after.substring(colonIdx + 1).trim();

                // Check if it's a tabular array header on the hyphen line
                if (isArrayHeader(valueRaw, keyRaw) || isArrayHeader(after, null)) {
                    String headerStr = isArrayHeader(valueRaw, keyRaw) ? valueRaw : after;
                    String actualKey = isArrayHeader(valueRaw, keyRaw) ? decodeKey(keyRaw, line.lineNo) : null;

                    JsonObject listObj = new JsonObject();
                    if (actualKey != null) {
                        JsonArray inner = decodeArrayBody(headerStr, bodyDepth + 2, line.lineNo);
                        listObj.fields.put(actualKey, inner);
                    }
                    // Collect sibling fields at bodyDepth + 1
                    collectObjectFields(listObj, bodyDepth + 1);
                    arr.elements.add(listObj);
                    continue;
                }

                // Regular object with first field on hyphen line
                JsonObject listObj = new JsonObject();
                String key = decodeKey(keyRaw, line.lineNo);

                if (valueRaw.isEmpty()) {
                    // nested object as first value
                    if (cursor < lines.size() && peek().depth > bodyDepth) {
                        listObj.fields.put(key, decodeObject(bodyDepth + 2));
                    } else {
                        listObj.fields.put(key, new JsonObject());
                    }
                } else if (valueRaw.startsWith("{") || valueRaw.startsWith("[")) {
                    try {
                        listObj.fields.put(key, JsonParser.parse(valueRaw));
                    } catch (JsonParseException e) {
                        listObj.fields.put(key, parsePrimitiveToken(valueRaw));
                    }
                } else {
                    listObj.fields.put(key, parsePrimitiveToken(valueRaw));
                }

                // Collect remaining fields at bodyDepth + 1
                collectObjectFields(listObj, bodyDepth + 1);
                arr.elements.add(listObj);
                continue;
            }

            // Plain primitive list item: - somevalue
            arr.elements.add(parsePrimitiveToken(after.trim()));
        }

        if (strict && ah.declaredLength >= 0 && itemsRead != ah.declaredLength) {
            throw new InteractumDecodeException(
                    "After line " + headerLineNo + ": declared " + ah.declaredLength +
                            " items but got " + itemsRead
            );
        }
        return arr;
    }

    /**
     * Collect additional object fields at a given depth into an existing JsonObject.
     * Used for list-item objects whose remaining fields follow the hyphen line.
     */
    void collectObjectFields(JsonObject obj, int depth) {
        while (cursor < lines.size()) {
            Line line = peek();
            if (line.depth != depth) break;
            if (line.isBlockClose()) {
                if (line.depth < depth) break; // belongs to a parent scope, don't touch it
                consume();                     // belongs to our scope or deeper, consume and stop
                break;
            } // leave --- for parent
            if (line.isListItem()) break;

            String content  = line.content;
            int    colonIdx = findUnquotedColon(content);
            if (colonIdx < 0) break;

            String keyRaw   = content.substring(0, colonIdx).trim();
            String valueRaw = content.substring(colonIdx + 1).trim();
            String key      = decodeKey(keyRaw, line.lineNo);
            consume();

            if (isArrayHeader(content, null)) {
                ArrayHeader ah = parseArrayHeader(content, line.lineNo);
                String actualKey = ah.key != null ? ah.key : decodeKey(keyRaw, line.lineNo);
                obj.fields.put(actualKey, decodeArrayBody(content, depth + 1, line.lineNo));
            } else if (valueRaw.isEmpty()) {
                if (cursor < lines.size() && peek().depth > depth) {
                    obj.fields.put(key, decodeObject(depth + 1));
                } else {
                    obj.fields.put(key, new JsonObject());
                }
            } else {
                obj.fields.put(key, parsePrimitiveToken(valueRaw));
            }
        }
    }

    // ─── Array Header Parsing ─────────────────────────────────────────────────

    static class ArrayHeader {
        String       key;           // may be null for root arrays
        int          declaredLength; // -1 = not declared ([] form)
        char         delimiter;
        List<String> fields;        // null = not tabular
        String       inlineValues;  // null = not inline primitive array
    }

    /**
     * Check if a string looks like an array header (contains [...]:).
     * keyHint is used to skip the key prefix before checking.
     */
    static boolean isArrayHeader(String content, String keyHint) {
        // Strip key prefix if we know the key
        String rest = content;
        if (keyHint != null && !keyHint.isEmpty()) {
            // Already stripped by caller
            rest = content;
        }
        // Look for pattern: optional-key [digits or empty] optional-fields :
        return ARRAY_HEADER_PATTERN.matcher(rest).find();
    }

    public static final Pattern ARRAY_HEADER_PATTERN = Pattern.compile(
            "^(?:[A-Za-z_\"][^\\[]*)?\\[(\\d*)([\\t|]?)\\](?:\\{[^}]*\\})?:\\s*(.*)$"
    );

    /**
     * Parse a full header string (key may be included) into an ArrayHeader.
     */
    ArrayHeader parseArrayHeader(String content, int lineNo) {
        ArrayHeader ah = new ArrayHeader();
        ah.delimiter = delimiter; // document default

        Matcher m = ARRAY_HEADER_PATTERN.matcher(content);
        if (!m.matches()) {
            throw new InteractumDecodeException(
                    "Line " + lineNo + ": invalid array header: " + content
            );
        }

        // Length
        String lenStr = m.group(1);
        ah.declaredLength = lenStr.isEmpty() ? -1 : Integer.parseInt(lenStr);

        // Delimiter override
        String delimStr = m.group(2);
        if (!delimStr.isEmpty()) {
            ah.delimiter = delimStr.equals("|") ? '|' : '\t';
        }

        // Inline values after the colon
        String afterColon = m.group(3).trim();

        // Extract key prefix (everything before the first [)
        int bracketIdx = content.indexOf('[');
        if (bracketIdx > 0) {
            String rawKey = content.substring(0, bracketIdx).trim();
            ah.key = rawKey.isEmpty() ? null : decodeKey(rawKey, lineNo);
        }

        // Extract fields from { } segment
        int closeB    = content.indexOf(']');
        int openBrace = content.indexOf('{', closeB);
        int closeBrace= content.indexOf('}', openBrace > -1 ? openBrace : 0);

        if (openBrace > -1 && closeBrace > openBrace) {
            String fieldStr = content.substring(openBrace + 1, closeBrace);
            List<String> raw = splitDelimited(fieldStr, ah.delimiter);
            ah.fields = new ArrayList<>();
            for (String f : raw) ah.fields.add(decodeKey(f.trim(), lineNo));
            ah.inlineValues = null; // tabular, not inline
        } else if (!afterColon.isEmpty()) {
            ah.inlineValues = afterColon; // inline primitive array
        } else {
            ah.inlineValues = null; // expanded list
        }

        return ah;
    }

    // ─── Tabular Row Disambiguation ───────────────────────────────────────────

    /**
     * A line is a tabular row if the first unquoted delimiter appears
     * before the first unquoted colon (or there is no unquoted colon).
     */
    static boolean isTabularRow(String content, char delim) {
        boolean inQuotes  = false;
        int     firstDelim = -1;
        int     firstColon = -1;

        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"') { inQuotes = !inQuotes; continue; }
            if (inQuotes) { if (c == '\\') i++; continue; }
            if (c == delim && firstDelim < 0) firstDelim = i;
            if (c == ':'   && firstColon < 0) firstColon = i;
        }

        if (firstDelim >= 0 && firstColon < 0) return true;   // has delim, no colon → row
        if (firstDelim >= 0 && firstDelim < firstColon) return true; // delim before colon → row
        return false;
    }

    // ─── Primitive Token Parsing ──────────────────────────────────────────────

    static JsonPrimitive parsePrimitiveToken(String token) {
        if (token == null || token.isEmpty()) return new JsonPrimitive("");

        // Quoted string
        if (token.startsWith("\"")) {
            if (!token.endsWith("\"") || token.length() < 2) {
                throw new InteractumDecodeException("Unterminated string: " + token);
            }
            String inner = token.substring(1, token.length() - 1);
            return new JsonPrimitive(unescapeString(inner));
        }

        // Boolean / null literals
        if (token.equals("true"))  return JsonPrimitive.TRUE;
        if (token.equals("false")) return JsonPrimitive.FALSE;
        if (token.equals("null"))  return JsonPrimitive.NULL;

        // Numeric detection
        if (isNumericToken(token)) {
            try {
                if (token.contains(".") || token.toLowerCase().contains("e")) {
                    return new JsonPrimitive(Double.parseDouble(token));
                }
                return new JsonPrimitive(Long.parseLong(token));
            } catch (NumberFormatException e) {
                // fall through to string
            }
        }

        // Bare string
        return new JsonPrimitive(token);
    }

    static boolean isNumericToken(String t) {
        if (t.isEmpty()) return false;
        // Must match: optional minus, digits, optional decimal, optional exponent
        // But NOT have forbidden leading zeros (e.g. "05")
        return t.matches("^-?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?$");
    }

    static String unescapeString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    default:
                        throw new InteractumDecodeException(
                                "Invalid escape sequence: \\" + next
                        );
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ─── Key Decoding ─────────────────────────────────────────────────────────

    static String decodeKey(String raw, int lineNo) {
        raw = raw.trim();
        if (raw.startsWith("\"")) {
            if (!raw.endsWith("\"") || raw.length() < 2) {
                throw new InteractumDecodeException(
                        "Line " + lineNo + ": unterminated quoted key: " + raw
                );
            }
            return unescapeString(raw.substring(1, raw.length() - 1));
        }
        return raw;
    }

    // ─── Delimiter-Aware Split ────────────────────────────────────────────────

    static List<String> splitDelimited(String s, char delim) {
        List<String> result   = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes      = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (inQuotes) {
                if (c == '\\' && i + 1 < s.length()) {
                    current.append(c).append(s.charAt(++i));
                } else {
                    current.append(c);
                }
            } else if (c == delim) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());
        return result;
    }

    // ─── Unquoted Colon Finder ────────────────────────────────────────────────

    /**
     * Find the first colon that is not inside a quoted string.
     * Returns -1 if not found.
     */
    public static int findUnquotedColon(String s) {
        boolean inQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') { inQuotes = !inQuotes; continue; }
            if (inQuotes) { if (c == '\\') i++; continue; }
            if (c == ':') return i;
        }
        return -1;
    }

    // ─── Cursor Helpers ───────────────────────────────────────────────────────

    Line peek() {
        return lines.get(cursor);
    }

    Line consume() {
        return lines.get(cursor++);
    }
}


