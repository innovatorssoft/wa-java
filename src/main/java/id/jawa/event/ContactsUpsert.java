package id.jawa.event;

import java.util.List;

/**
 * Payload for Baileys {@code contacts.upsert}.
 * <p>
 * Baileys shape: {@code Contact[]} with {@code {id, name?, username?, lid?, phoneNumber?}}.
 * <p>
 * <p>
 * Increment 2: dispatched by InMemoryStore when bound (see WaEventListener).
 *
 * @param contacts upserted contacts
 */
public record ContactsUpsert(List<Contact> contacts) {
    public ContactsUpsert {
        contacts = contacts == null ? List.of() : List.copyOf(contacts);
    }

    /**
     * One Baileys contact.
     *
     * @param id contact JID
     * @param name display name, null when absent
     * @param username username, null when absent
     * @param lid linked-identity JID, null when absent
     * @param phoneNumber phone number, null when absent
     * @param imgUrl profile picture URL, null when unknown (Baileys contacts
     *               carry this; it is filled from picture notifications and
     *               profile fetches, not from sync upserts)
     */
    public record Contact(String id, String name, String username, String lid, String phoneNumber,
                          String imgUrl) {
        /** Convenience constructor without a picture URL. */
        public Contact(String id, String name, String username, String lid, String phoneNumber) {
            this(id, name, username, lid, phoneNumber, null);
        }
    }
}
