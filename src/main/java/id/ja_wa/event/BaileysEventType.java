package id.ja_wa.event;

import java.util.HashMap;
import java.util.Map;

/**
 * The Baileys event names, one constant per event.
 * <p>
 * Source of the names: the {@code ev.emit(...)} sites in Baileys
 * (see {@code EVENT_COMPATIBILITY_MATRIX.md} in the baileys-port workspace).
 * There are 33 user-facing events plus the internal {@code event} fan-out
 * name. No constant has been invented: {@link #fromName(String)} returns
 * {@code null} for any name that is not one of the 34 below.
 */
public enum BaileysEventType {

    /** Baileys {@code connection.update}. */
    CONNECTION_UPDATE("connection.update"),
    /** Baileys {@code creds.update}. */
    CREDS_UPDATE("creds.update"),
    /** Baileys {@code messaging-history.set}. */
    MESSAGING_HISTORY_SET("messaging-history.set"),
    /** Baileys {@code messaging-history.status}. */
    MESSAGING_HISTORY_STATUS("messaging-history.status"),
    /** Baileys {@code chats.upsert}. */
    CHATS_UPSERT("chats.upsert"),
    /** Baileys {@code chats.update}. */
    CHATS_UPDATE("chats.update"),
    /** Baileys {@code chats.delete}. */
    CHATS_DELETE("chats.delete"),
    /** Baileys {@code chats.lock}. */
    CHATS_LOCK("chats.lock"),
    /** Baileys {@code lid-mapping.update} (a single object, not an array). */
    LID_MAPPING_UPDATE("lid-mapping.update"),
    /** Baileys {@code presence.update}. */
    PRESENCE_UPDATE("presence.update"),
    /** Baileys {@code contacts.upsert}. */
    CONTACTS_UPSERT("contacts.upsert"),
    /** Baileys {@code contacts.update}. */
    CONTACTS_UPDATE("contacts.update"),
    /** Baileys {@code messages.upsert}. */
    MESSAGES_UPSERT("messages.upsert"),
    /** Baileys {@code messages.update}. */
    MESSAGES_UPDATE("messages.update"),
    /** Baileys {@code messages.delete}. */
    MESSAGES_DELETE("messages.delete"),
    /** Baileys {@code messages.media-update}. */
    MESSAGES_MEDIA_UPDATE("messages.media-update"),
    /** Baileys {@code messages.reaction}. */
    MESSAGES_REACTION("messages.reaction"),
    /** Baileys {@code message-receipt.update}. */
    MESSAGE_RECEIPT_UPDATE("message-receipt.update"),
    /** Baileys {@code groups.upsert}. */
    GROUPS_UPSERT("groups.upsert"),
    /** Baileys {@code groups.update}. */
    GROUPS_UPDATE("groups.update"),
    /** Baileys {@code group-participants.update}. */
    GROUP_PARTICIPANTS_UPDATE("group-participants.update"),
    /** Baileys {@code group.join-request}. */
    GROUP_JOIN_REQUEST("group.join-request"),
    /** Baileys {@code group.member-tag.update}. */
    GROUP_MEMBER_TAG_UPDATE("group.member-tag.update"),
    /** Baileys {@code blocklist.update} (one emission per JID). */
    BLOCKLIST_UPDATE("blocklist.update"),
    /** Baileys {@code call}. */
    CALL("call"),
    /** Baileys {@code labels.edit}. */
    LABELS_EDIT("labels.edit"),
    /** Baileys {@code labels.association}. */
    LABELS_ASSOCIATION("labels.association"),
    /** Baileys {@code newsletter.reaction}. */
    NEWSLETTER_REACTION("newsletter.reaction"),
    /** Baileys {@code newsletter.view}. */
    NEWSLETTER_VIEW("newsletter.view"),
    /** Baileys {@code newsletter-participants.update}. */
    NEWSLETTER_PARTICIPANTS_UPDATE("newsletter-participants.update"),
    /** Baileys {@code newsletter-settings.update}. */
    NEWSLETTER_SETTINGS_UPDATE("newsletter-settings.update"),
    /** Baileys {@code settings.update}. */
    SETTINGS_UPDATE("settings.update"),
    /** Baileys {@code message-capping.update} (raw mex payload, verbatim). */
    MESSAGE_CAPPING_UPDATE("message-capping.update"),
    /**
     * Baileys internal {@code event} fan-out map. It exists for completeness
     * only: {@link WaEventBus} deliberately does <em>not</em> re-emit every
     * event through it (that would double-deliver). Consumers that want one
     * method for all events should use {@link WaEventListener#onEvent}.
     */
    EVENT("event");

    private static final Map<String, BaileysEventType> BY_NAME = new HashMap<>();

    static {
        for (BaileysEventType type : values()) {
            BY_NAME.put(type.baileysName, type);
        }
    }

    private final String baileysName;

    BaileysEventType(String baileysName) {
        this.baileysName = baileysName;
    }

    /** @return the exact Baileys string name for this event. */
    public String baileysName() {
        return baileysName;
    }

    /**
     * Maps a Baileys string name to its constant.
     *
     * @param name Baileys event name, or {@code null}
     * @return the matching constant, or {@code null} for unknown names
     *         (never an invented constant)
     */
    public static BaileysEventType fromName(String name) {
        if (name == null) return null;
        return BY_NAME.get(name);
    }
}
