package id.jawa.event;

/**
 * Payload for Baileys {@code labels.association}.
 * <p>
 * Baileys shape: {@code {type: 'add'|'remove', association:
 * {type: 'label_jid', chatId, labelId} | {type: 'label_message', chatId, messageId?, labelId}}}.
 * The two association variants are flattened here: {@code associationType}
 * distinguishes them and {@code messageId} is null for the
 * {@code label_jid} variant.
 * <p>
 * Increment 2: dispatched (see WaEventListener for the dispatch sites).
 *
 * @param type {@code add} or {@code remove}
 * @param associationType {@code label_jid} or {@code label_message}
 * @param chatId associated chat JID
 * @param labelId label id
 * @param messageId associated message id, null for the {@code label_jid} variant
 */
public record LabelsAssociation(String type, String associationType, String chatId,
                                String labelId, String messageId) {}
