package id.jawa.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Multi-listener registry for Baileys-shaped events.
 * <p>
 * Dispatch is synchronous on the caller's thread (the wa-java reader thread
 * for protocol events, matching the existing {@code JaWaClient.Listener}
 * discipline). Each listener's typed method <em>and</em> its generic
 * {@link WaEventListener#onEvent(BaileysEventType, Object)} fallback are
 * invoked, each in its own try/catch: a throwing listener is logged with
 * {@link java.util.logging} and never prevents delivery to the next
 * listener or breaks the reader loop. This package intentionally uses
 * {@code java.util.logging}, not SLF4J, so it adds no dependency.
 * <p>
 * The internal {@link BaileysEventType#EVENT} fan-out is deliberately
 * <em>not</em> re-emitted: every event is dispatched once, under its own
 * type, and listeners that want one method for all events use
 * {@code onEvent}.
 */
public final class WaEventBus {

    private static final Logger LOG = Logger.getLogger(WaEventBus.class.getName());

    private final CopyOnWriteArrayList<WaEventListener> listeners = new CopyOnWriteArrayList<>();

    /** @param listener listener to register; null is ignored */
    public void addListener(WaEventListener listener) {
        if (listener != null) listeners.addIfAbsent(listener);
    }

    /** @param listener listener to unregister; null is ignored */
    public void removeListener(WaEventListener listener) {
        if (listener != null) listeners.remove(listener);
    }

    /** @return number of currently registered listeners */
    public int listenerCount() {
        return listeners.size();
    }

    /** @return an unmodifiable snapshot of the registered listeners */
    public List<WaEventListener> listeners() {
        return List.copyOf(listeners);
    }

    /**
     * Dispatches an event synchronously to every registered listener:
     * first the typed method for {@code type}, then the generic
     * {@code onEvent} fallback, each guarded so one listener's failure
     * cannot affect the others.
     *
     * @param type event type; null dispatches nothing
     * @param payload event payload; a payload of the wrong type for
     *                {@code type} is logged per listener and skipped for
     *                that listener's typed method (its generic fallback
     *                still fires)
     */
    public void dispatch(BaileysEventType type, Object payload) {
        if (type == null) return;
        for (WaEventListener listener : listeners) {
            try {
                dispatchTyped(listener, type, payload);
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "WaEventListener threw for " + type.baileysName(), t);
            }
            try {
                listener.onEvent(type, payload);
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "WaEventListener.onEvent threw for " + type.baileysName(), t);
            }
        }
    }

    private static void dispatchTyped(WaEventListener listener, BaileysEventType type, Object payload) {
        switch (type) {
            case CONNECTION_UPDATE -> listener.onConnectionUpdate((ConnectionUpdate) payload);
            case CREDS_UPDATE -> listener.onCredsUpdate((CredsUpdate) payload);
            case MESSAGING_HISTORY_SET -> listener.onMessagingHistorySet((MessagingHistorySet) payload);
            case MESSAGING_HISTORY_STATUS -> listener.onMessagingHistoryStatus((MessagingHistoryStatus) payload);
            case CHATS_UPSERT -> listener.onChatsUpsert((ChatsUpsert) payload);
            case CHATS_UPDATE -> listener.onChatsUpdate((ChatsUpdate) payload);
            case CHATS_DELETE -> listener.onChatsDelete((ChatsDelete) payload);
            case CHATS_LOCK -> listener.onChatsLock((ChatsLock) payload);
            case LID_MAPPING_UPDATE -> listener.onLidMappingUpdate((LidMappingUpdate) payload);
            case PRESENCE_UPDATE -> listener.onPresenceUpdate((PresenceUpdate) payload);
            case CONTACTS_UPSERT -> listener.onContactsUpsert((ContactsUpsert) payload);
            case CONTACTS_UPDATE -> listener.onContactsUpdate((ContactsUpdate) payload);
            case MESSAGES_UPSERT -> listener.onMessageUpsert((MessageUpsert) payload);
            case MESSAGES_UPDATE -> listener.onMessagesUpdate((MessagesUpdate) payload);
            case MESSAGES_DELETE -> listener.onMessagesDelete((MessagesDelete) payload);
            case MESSAGES_MEDIA_UPDATE -> listener.onMessagesMediaUpdate((MessagesMediaUpdate) payload);
            case MESSAGES_REACTION -> listener.onMessagesReaction((MessagesReaction) payload);
            case MESSAGE_RECEIPT_UPDATE -> listener.onMessageReceiptUpdate((MessageReceiptUpdate) payload);
            case GROUPS_UPSERT -> listener.onGroupsUpsert((GroupsUpsert) payload);
            case GROUPS_UPDATE -> listener.onGroupsUpdate((GroupsUpdate) payload);
            case GROUP_PARTICIPANTS_UPDATE -> listener.onGroupParticipantsUpdate((GroupParticipantsUpdate) payload);
            case GROUP_JOIN_REQUEST -> listener.onGroupJoinRequest((GroupJoinRequest) payload);
            case GROUP_MEMBER_TAG_UPDATE -> listener.onGroupMemberTagUpdate((GroupMemberTagUpdate) payload);
            case BLOCKLIST_UPDATE -> listener.onBlocklistUpdate((BlocklistUpdate) payload);
            case CALL -> listener.onCall((CallEvent) payload);
            case LABELS_EDIT -> listener.onLabelsEdit((LabelsEdit) payload);
            case LABELS_ASSOCIATION -> listener.onLabelsAssociation((LabelsAssociation) payload);
            case NEWSLETTER_REACTION -> listener.onNewsletterReaction((NewsletterReaction) payload);
            case NEWSLETTER_VIEW -> listener.onNewsletterView((NewsletterView) payload);
            case NEWSLETTER_PARTICIPANTS_UPDATE -> listener.onNewsletterParticipantsUpdate((NewsletterParticipantsUpdate) payload);
            case NEWSLETTER_SETTINGS_UPDATE -> listener.onNewsletterSettingsUpdate((NewsletterSettingsUpdate) payload);
            case SETTINGS_UPDATE -> listener.onSettingsUpdate((SettingsUpdate) payload);
            case MESSAGE_CAPPING_UPDATE -> listener.onMessageCappingUpdate((MessageCappingUpdate) payload);
            case EVENT -> {
                // Internal fan-out only: no typed method exists for it, and the
                // bus never re-emits other events through it. onEvent (above)
                // is the only delivery for this type.
            }
        }
    }
}
