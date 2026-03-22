package io.interactum.model;

import java.util.ArrayList;
import java.util.List;

public class JsonArray extends JsonValue {

    public final List<JsonValue> elements;

    public JsonArray() {
        this.elements = new ArrayList<>();
    }

    @Override
    public String toString() {
        return "Array[" + elements.size() + "]";
    }
}
