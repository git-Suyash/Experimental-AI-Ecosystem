package io.interactum.util;

import io.interactum.model.*;
import io.interactum.ecoder.*;
import java.util.*;

/**
 * JsonSerializer — converts a JsonValue tree back to a JSON string.
 * Supports both compact (single-line) and pretty-printed output.
 */
public class JsonSerializer {

    public static String toJson(JsonValue value, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        serialize(value, sb, 0, pretty);
        return sb.toString();
    }

    static void serialize(JsonValue value, StringBuilder sb, int depth, boolean pretty) {
        if (value instanceof JsonPrimitive) {
            serializePrimitive((JsonPrimitive) value, sb);

        } else if (value instanceof JsonObject) {
            serializeObject((JsonObject) value, sb, depth, pretty);

        } else if (value instanceof JsonArray) {
            serializeArray((JsonArray) value, sb, depth, pretty);
        }
    }

    static void serializePrimitive(JsonPrimitive p, StringBuilder sb) {
        if (p.value == null)            { sb.append("null"); return; }
        if (p.value instanceof Boolean) { sb.append(p.value); return; }
        if (p.value instanceof Number)  { sb.append(InteractumEncoder.normalizeNumber((Number) p.value)); return; }
        // String
        sb.append('"').append(InteractumEncoder.escapeString((String) p.value)).append('"');
    }

    static void serializeObject(JsonObject obj, StringBuilder sb, int depth, boolean pretty) {
        if (obj.fields.isEmpty()) { sb.append("{}"); return; }

        sb.append("{");
        boolean first = true;
        String indent   = pretty ? "\n" + "  ".repeat(depth + 1) : "";
        String closing  = pretty ? "\n" + "  ".repeat(depth) + "}" : "}";
        String sep      = pretty ? ": " : ":";

        for (Map.Entry<String, JsonValue> e : obj.fields.entrySet()) {
            if (!first) sb.append(",");
            sb.append(indent);
            sb.append('"').append(InteractumEncoder.escapeString(e.getKey())).append('"');
            sb.append(sep);
            serialize(e.getValue(), sb, depth + 1, pretty);
            first = false;
        }
        sb.append(closing);
    }

    static void serializeArray(JsonArray arr, StringBuilder sb, int depth, boolean pretty) {
        if (arr.elements.isEmpty()) { sb.append("[]"); return; }

        sb.append("[");
        boolean first   = true;
        String indent   = pretty ? "\n" + "  ".repeat(depth + 1) : "";
        String closing  = pretty ? "\n" + "  ".repeat(depth) + "]" : "]";

        for (JsonValue elem : arr.elements) {
            if (!first) sb.append(",");
            sb.append(indent);
            serialize(elem, sb, depth + 1, pretty);
            first = false;
        }
        sb.append(closing);
    }
}

