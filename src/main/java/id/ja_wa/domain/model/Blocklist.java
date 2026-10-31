package id.ja_wa.domain.model;

import java.util.List;

/**
 * Represents the WhatsApp account blocklist state and synchronized contacts.
 *
 * @param dhash The hash token representing the current state of the blocklist
 * @param addressingMode Addressing mode used by WhatsApp servers (e.g. "pn" or "lid")
 * @param items List of blocked contacts and their respective properties
 */
public record Blocklist(
    String dhash,
    String addressingMode,
    List<BlocklistItem> items
) {}
