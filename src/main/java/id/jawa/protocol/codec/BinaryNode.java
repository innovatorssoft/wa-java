package id.jawa.protocol.codec;

import id.jawa.protocol.connection.*;
import id.jawa.protocol.codec.*;
import id.jawa.protocol.crypto.*;
import id.jawa.domain.model.*;
import id.jawa.domain.store.*;
import id.jawa.feature.pairing.*;
import id.jawa.feature.messaging.*;
import id.jawa.feature.media.*;
import id.jawa.feature.appstate.*;
import id.jawa.feature.signal.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record BinaryNode(String tag, Map<String, String> attrs, Object content) {

    public BinaryNode {
        Objects.requireNonNull(tag, "tag");
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);

        if (content != null
                && !(content instanceof String)
                && !(content instanceof byte[])
                && !(content instanceof List<?>)) {
            throw new IllegalArgumentException(
                "content must be null, String, byte[], or List<BinaryNode>, got: " + content.getClass());
        }
    }

    public static BinaryNode of(String tag) {
        return new BinaryNode(tag, Map.of(), null);
    }

    public static BinaryNode of(String tag, Map<String, String> attrs) {
        return new BinaryNode(tag, attrs, null);
    }

    public static BinaryNode text(String tag, Map<String, String> attrs, String text) {
        return new BinaryNode(tag, attrs, text);
    }

    public static BinaryNode bytes(String tag, Map<String, String> attrs, byte[] data) {
        return new BinaryNode(tag, attrs, data);
    }

    public static BinaryNode children(String tag, Map<String, String> attrs, List<BinaryNode> kids) {
        return new BinaryNode(tag, attrs, kids == null ? null : List.copyOf(kids));
    }

    public String attr(String key) { return attrs.get(key); }
    public String attr(String key, String def) { return attrs.getOrDefault(key, def); }

    @SuppressWarnings("unchecked")
    public List<BinaryNode> childrenList() {
        return content instanceof List<?> l ? (List<BinaryNode>) l : List.of();
    }

    public String textContent() {
        return switch (content) {
            case null -> null;
            case String s -> s;
            case byte[] b -> new String(b, java.nio.charset.StandardCharsets.UTF_8);
            default -> throw new IllegalStateException(
                "content is not text/bytes: " + content.getClass());
        };
    }

    public BinaryNode child(String tag) {
        for (BinaryNode k : childrenList()) if (tag.equals(k.tag())) return k;
        return null;
    }

    public List<BinaryNode> children(String tag) {
        List<BinaryNode> out = new java.util.ArrayList<>();
        for (BinaryNode k : childrenList()) if (tag.equals(k.tag())) out.add(k);
        return out;
    }

    public byte[] bytesContent() {
        return switch (content) {
            case null -> null;
            case byte[] b -> b;
            case String s -> s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            default -> throw new IllegalStateException(
                "content is not text/bytes: " + content.getClass());
        };
    }
}
