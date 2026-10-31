package id.ja_wa.protocol.codec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fundamental XML-like hierarchical stanza node exchanged in the WhatsApp Web binary protocol.
 *
 * @param tag XML tag string identifying this node
 * @param attrs Key-value attribute map associated with the tag
 * @param content Node payload: null, String, byte[], or List of child BinaryNode instances
 */
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

    /**
     * Creates an empty leaf node with the specified tag.
     *
     * @param tag Tag name
     * @return New BinaryNode instance
     */
    public static BinaryNode of(String tag) {
        return new BinaryNode(tag, Map.of(), null);
    }

    /**
     * Creates a leaf node with tag and attributes.
     *
     * @param tag Tag name
     * @param attrs Attribute map
     * @return New BinaryNode instance
     */
    public static BinaryNode of(String tag, Map<String, String> attrs) {
        return new BinaryNode(tag, attrs, null);
    }

    /**
     * Creates a node containing text content.
     *
     * @param tag Tag name
     * @param attrs Attribute map
     * @param text Text body
     * @return New BinaryNode instance
     */
    public static BinaryNode text(String tag, Map<String, String> attrs, String text) {
        return new BinaryNode(tag, attrs, text);
    }

    /**
     * Creates a node containing raw binary data content.
     *
     * @param tag Tag name
     * @param attrs Attribute map
     * @param data Binary payload bytes
     * @return New BinaryNode instance
     */
    public static BinaryNode bytes(String tag, Map<String, String> attrs, byte[] data) {
        return new BinaryNode(tag, attrs, data);
    }

    /**
     * Creates a composite node containing nested child nodes.
     *
     * @param tag Tag name
     * @param attrs Attribute map
     * @param kids List of child BinaryNode elements
     * @return New BinaryNode instance
     */
    public static BinaryNode children(String tag, Map<String, String> attrs, List<BinaryNode> kids) {
        return new BinaryNode(tag, attrs, kids == null ? null : List.copyOf(kids));
    }

    /**
     * Retrieves an attribute value by key.
     *
     * @param key Attribute key name
     * @return Attribute string value, or null if key does not exist
     */
    public String attr(String key) { return attrs.get(key); }

    /**
     * Retrieves an attribute value by key with a fallback default.
     *
     * @param key Attribute key name
     * @param def Default value to return if attribute is absent
     * @return Attribute string value or default value
     */
    public String attr(String key, String def) { return attrs.getOrDefault(key, def); }

    /**
     * Returns an unmodifiable list of child nodes if content is a list.
     *
     * @return List of child nodes, or empty list if content is not a list
     */
    @SuppressWarnings("unchecked")
    public List<BinaryNode> childrenList() {
        return content instanceof List<?> l ? (List<BinaryNode>) l : List.of();
    }

    /**
     * Extracts content decoded as a UTF-8 string.
     *
     * @return String content or null
     */
    public String textContent() {
        return switch (content) {
            case null -> null;
            case String s -> s;
            case byte[] b -> new String(b, StandardCharsets.UTF_8);
            default -> throw new IllegalStateException(
                "content is not text/bytes: " + content.getClass());
        };
    }

    /**
     * Finds the first direct child matching the given tag.
     *
     * @param tag Child tag name to find
     * @return First matching child node, or null
     */
    public BinaryNode child(String tag) {
        for (BinaryNode k : childrenList()) if (tag.equals(k.tag())) return k;
        return null;
    }

    /**
     * Finds all direct children matching the given tag.
     *
     * @param tag Child tag name to filter by
     * @return List of matching child nodes
     */
    public List<BinaryNode> children(String tag) {
        List<BinaryNode> out = new ArrayList<>();
        for (BinaryNode k : childrenList()) if (tag.equals(k.tag())) out.add(k);
        return out;
    }

    /**
     * Extracts content as raw bytes.
     *
     * @return Byte array content or null
     */
    public byte[] bytesContent() {
        return switch (content) {
            case null -> null;
            case byte[] b -> b;
            case String s -> s.getBytes(StandardCharsets.UTF_8);
            default -> throw new IllegalStateException(
                "content is not text/bytes: " + content.getClass());
        };
    }
}
