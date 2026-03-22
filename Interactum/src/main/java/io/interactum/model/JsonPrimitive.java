package io.interactum.model;

public class JsonPrimitive extends JsonValue {

    public static final JsonPrimitive NULL  = new JsonPrimitive(null);
    public static final JsonPrimitive TRUE  = new JsonPrimitive(Boolean.TRUE);
    public static final JsonPrimitive FALSE = new JsonPrimitive(Boolean.FALSE);

    public final Object value; // null | Boolean | Number | String

    public JsonPrimitive(Object value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return "Primitive(" + value + ")";
    }
}
