package io.interactum.model;

import java.util.LinkedHashMap;

public class JsonObject extends JsonValue {

    // LinkedHashMap preserves insertion order
    public final LinkedHashMap<String, JsonValue> fields;

    public JsonObject() {
        this.fields = new LinkedHashMap<>();
    }

    public JsonObject(LinkedHashMap<String, JsonValue> fields) {
        this.fields = new LinkedHashMap<>(fields);
    }

    @Override
    public String toString() {
        return "Object(" + fields.keySet() + ")";
    }
}
