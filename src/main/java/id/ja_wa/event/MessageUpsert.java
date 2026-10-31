package id.ja_wa.event;

import id.ja_wa.feature.messaging.MessageReceiver;

import java.util.List;

/**
 * Payload for Baileys {@code messages.upsert}.
 * <p>
 * Baileys shape: {@code {messages: WAMessage[], type: 'notify' | caller-supplied}}.
 * Only the literal {@code 'notify'} was found in Baileys code; {@code type}
 * is therefore kept as an open string, not a closed enum.
 * <p>
 * Increment 8: Baileys' PDO-response upsert
 * ({@code lib/Utils/process-message.js}, the
 * {@code PEER_DATA_OPERATION_REQUEST_RESPONSE_MESSAGE} case) also carries
 * {@code requestId: response.stanzaId}. That is the new trailing
 * {@code requestId} component (null for every other upsert kind). The
 * two-argument constructor is kept as a delegating overload so existing
 * call sites are unaffected and normal inbound messages keep the
 * default null behaviour.
 *
 * @param messages decoded messages, reusing wa-java's existing
 *                 {@link MessageReceiver.Decoded} model rather than
 *                 duplicating it
 * @param type Baileys {@code type}; {@code "notify"} for the JaWaClient
 *             inbound-message path
 * @param requestId Baileys {@code requestId} ({@code response.stanzaId})
 *                  for PDO-response upserts; null otherwise
 */
public record MessageUpsert(
    List<MessageReceiver.Decoded> messages,
    String type,
    String requestId
) {
    public MessageUpsert {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /** Pre-Increment 8 constructor: same upsert with no request id. */
    public MessageUpsert(List<MessageReceiver.Decoded> messages, String type) {
        this(messages, type, null);
    }
}
