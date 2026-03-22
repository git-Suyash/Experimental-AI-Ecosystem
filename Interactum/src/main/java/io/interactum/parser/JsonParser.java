package io.interactum.parser;

import java.util.*;
import io.interactum.model.*;
/**
 * Hand-written recursive descent JSON parser.
 * Supports: objects, arrays, strings, numbers, booleans, null.
 * Preserves insertion order for object keys via LinkedHashMap.
 */
public class JsonParser {

    private final String input;
    private int pos;

    private JsonParser(String input) {
        this.input = input;
        this.pos = 0;
    }

    public static JsonValue parse(String input) {
        JsonParser parser = new JsonParser(input.trim());
        JsonValue result = parser.parseValue();
        parser.skipWhitespace();
        if (parser.pos < parser.input.length()) {
            throw new JsonParseException(
                    "Unexpected characters after root value at position " + parser.pos
            );
        }
        return result;
    }

    // ─── Core Parse Methods ───────────────────────────────────────────────────

    JsonValue parseValue() {
        skipWhitespace();
        if (pos >= input.length()) {
            throw new JsonParseException("Unexpected end of input");
        }

        char c = peek();
        switch (c) {
            case '{': return parseObject();
            case '[': return parseArray();
            case '"': return new JsonPrimitive(parseString());
            case 't': return parseLiteral("true",  JsonPrimitive.TRUE);
            case 'f': return parseLiteral("false", JsonPrimitive.FALSE);
            case 'n': return parseLiteral("null",  JsonPrimitive.NULL);
            default:
                if (c == '-' || Character.isDigit(c)) return parseNumber();
                throw new JsonParseException("Unexpected character '" + c + "' at position " + pos);
        }
    }

    JsonObject parseObject() {
        expect('{');
        JsonObject obj = new JsonObject();
        skipWhitespace();

        if (peek() == '}') {
            pos++;
            return obj;
        }

        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new JsonParseException(
                        "Expected string key in object at position " + pos
                );
            }
            String key = parseString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            JsonValue value = parseValue();
            obj.fields.put(key, value);

            skipWhitespace();
            char next = peek();
            if (next == '}') { pos++; break; }
            if (next == ',') { pos++; continue; }
            throw new JsonParseException(
                    "Expected ',' or '}' in object at position " + pos
            );
        }

        return obj;
    }

    JsonArray parseArray() {
        expect('[');
        JsonArray arr = new JsonArray();
        skipWhitespace();

        if (peek() == ']') {
            pos++;
            return arr;
        }

        while (true) {
            skipWhitespace();
            arr.elements.add(parseValue());
            skipWhitespace();
            char next = peek();
            if (next == ']') { pos++; break; }
            if (next == ',') { pos++; continue; }
            throw new JsonParseException(
                    "Expected ',' or ']' in array at position " + pos
            );
        }

        return arr;
    }

    String parseString() {
        expect('"');
        StringBuilder sb = new StringBuilder();

        while (pos < input.length()) {
            char c = input.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= input.length()) break;
                char esc = input.charAt(pos++);
                switch (esc) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case '/':  sb.append('/');  break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        String hex = input.substring(pos, Math.min(pos + 4, input.length()));
                        if (hex.length() < 4) throw new JsonParseException("Invalid \\u escape");
                        sb.append((char) Integer.parseInt(hex, 16));
                        pos += 4;
                        break;
                    default:
                        throw new JsonParseException("Invalid escape \\" + esc);
                }
            } else {
                sb.append(c);
            }
        }
        throw new JsonParseException("Unterminated string");
    }

    JsonPrimitive parseNumber() {
        int start = pos;

        if (peek() == '-') pos++;

        // Integer part
        if (pos >= input.length()) throw new JsonParseException("Invalid number");
        if (input.charAt(pos) == '0') {
            pos++;
        } else if (Character.isDigit(input.charAt(pos))) {
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        } else {
            throw new JsonParseException("Invalid number at position " + pos);
        }

        // Fractional part
        if (pos < input.length() && input.charAt(pos) == '.') {
            pos++;
            if (pos >= input.length() || !Character.isDigit(input.charAt(pos))) {
                throw new JsonParseException("Invalid number: missing digits after decimal point");
            }
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        }

        // Exponent
        if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
            pos++;
            if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
            if (pos >= input.length() || !Character.isDigit(input.charAt(pos))) {
                throw new JsonParseException("Invalid exponent");
            }
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
        }

        String numStr = input.substring(start, pos);

        // Try long first, then double
        try {
            // Check for fractional or exponent → must be double
            if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                return new JsonPrimitive(Double.parseDouble(numStr));
            }
            return new JsonPrimitive(Long.parseLong(numStr));
        } catch (NumberFormatException e) {
            // Fallback to double for very large numbers
            try {
                return new JsonPrimitive(Double.parseDouble(numStr));
            } catch (NumberFormatException e2) {
                throw new JsonParseException("Invalid number: " + numStr);
            }
        }
    }

    JsonPrimitive parseLiteral(String literal, JsonPrimitive result) {
        if (input.startsWith(literal, pos)) {
            pos += literal.length();
            return result;
        }
        throw new JsonParseException(
                "Expected '" + literal + "' at position " + pos
        );
    }

    // ─── Utility ──────────────────────────────────────────────────────────────

    char peek() {
        if (pos >= input.length()) throw new JsonParseException("Unexpected end of input");
        return input.charAt(pos);
    }

    void expect(char c) {
        if (pos >= input.length() || input.charAt(pos) != c) {
            throw new JsonParseException(
                    "Expected '" + c + "' at position " + pos +
                            (pos < input.length() ? " but got '" + input.charAt(pos) + "'" : " (end of input)")
            );
        }
        pos++;
    }

    void skipWhitespace() {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
            else break;
        }
    }
}
