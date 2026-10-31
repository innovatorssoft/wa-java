package id.ja_wa.event;

import java.util.List;

/**
 * Payload for Baileys {@code contacts.update}.
 * <p>
 * Baileys shape: {@code Partial<Contact>[]}, observed as e.g.
 * {@code {id, notify, verifiedName}} and {@code {id, imgUrl: 'changed'|'removed'}}.
 * <p>
 * Increment 2: dispatched (see WaEventListener for the dispatch sites).
 *
 * @param updates partial contact updates
 */
public record ContactsUpdate(List<ContactUpdate> updates) {
    public ContactsUpdate {
        updates = updates == null ? List.of() : List.copyOf(updates);
    }

    /**
     * One partial contact update; unset fields are null.
     *
     * @param id contact JID
     * @param notifyName Baileys {@code notify} field (the contact's notify name),
     *                   renamed because {@code notify} is a final method of
     *                   {@link Object} and cannot be a record component; null
     *                   when not carried
     * @param verifiedName verified name, null when not carried
     * @param imgUrl {@code changed} or {@code removed}, null when not carried
     */
    public record ContactUpdate(String id, String notifyName, String verifiedName, String imgUrl) {}
}
