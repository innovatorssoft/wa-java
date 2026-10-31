package id.ja_wa.event;

/**
 * Payload for Baileys {@code labels.edit}.
 * <p>
 * Baileys shape: {@code {id, name, color, deleted, predefinedId?}}.
 * <p>
 * Increment 2: dispatched (see WaEventListener for the dispatch sites).
 *
 * @param id label id
 * @param name label name
 * @param color label color string
 * @param deleted whether the label was deleted
 * @param predefinedId predefined label id, null when absent
 */
public record LabelsEdit(String id, String name, String color, boolean deleted, String predefinedId) {}
