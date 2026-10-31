package id.ja_wa.event;

import java.util.List;

/**
 * Payload for Baileys {@code blocklist.update}.
 * <p>
 * Baileys shape: {@code {blocklist: [singleJid], type: 'add'|'remove'}} —
 * one emission per JID, not one event for the whole list.
 * <p>
 * Increment 2: dispatched (see WaEventListener for the dispatch sites).
 *
 * @param blocklist one-element list holding the single affected JID
 * @param type {@code add} or {@code remove}
 */
public record BlocklistUpdate(List<String> blocklist, String type) {
    public BlocklistUpdate {
        blocklist = blocklist == null ? List.of() : List.copyOf(blocklist);
    }
}
