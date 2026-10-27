package id.jawa.event;

import id.jawa.domain.model.Receipt;

/**
 * Payload for Baileys {@code message-receipt.update}.
 * <p>
 * Baileys shape: {@code {key, receipt: {userJid, [receiptType]: timestamp}}[]}.
 * wa-java already models a parsed receipt as {@link Receipt}
 * ({@code type, chatJid, senderJid, recipientJid, msgIds, timestamp} where
 * {@code timestamp} is epoch seconds from the stanza {@code attrs.t}); this
 * record wraps that model instead of duplicating it. The Baileys
 * {@code key} (message id / remote JID / fromMe / participant) is available
 * through the wrapped receipt's {@code msgIds}, {@code chatJid} and
 * {@code senderJid} fields — wa-java has no separate message-key record, so
 * no separate key field is invented here.
 *
 * @param receipt the parsed receipt (never null for a dispatched event)
 */
public record MessageReceiptUpdate(
    Receipt receipt
) {}
