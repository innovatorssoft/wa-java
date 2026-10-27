package id.jawa.event;

/**
 * Baileys-shaped event listener, delivered through {@link WaEventBus}.
 * <p>
 * Every method is a default no-op: implement only the events you need.
 * Each method's Javadoc states plainly whether JaWaClient currently
 * dispatches that event. Subscribing to an event that is not yet dispatched
 * yields no emissions — that is a documented gap, not a silent promise.
 * The internal Baileys {@code event} fan-out has no typed method here:
 * {@link #onEvent(BaileysEventType, Object)} is the generic fallback and
 * the bus deliberately does not re-emit every event through
 * {@link BaileysEventType#EVENT}.
 */
public interface WaEventListener {

    /**
     * Baileys {@code connection.update}.
     * <p>Dispatched by JaWaClient: {@code qr} (pair-device), {@code open}
     * (success), {@code close} + termination detail (failure / stream
     * error), and the {@code isNewLogin} partial (pair-success). The
     * {@code connecting}, {@code isOnline},
     * {@code receivedPendingNotifications} and {@code reachoutTimeLock}
     * variants are modelled in {@link ConnectionUpdate} but not yet emitted
     * (no wa-java source data for them).
     */
    default void onConnectionUpdate(ConnectionUpdate update) {}

    /**
     * Baileys {@code creds.update}.
     * <p>Dispatched by JaWaClient: the paired {@code me} identity only
     * (pair-success). Full-creds and other partial updates are not yet emitted.
     */
    default void onCredsUpdate(CredsUpdate update) {}

    /**
     * Baileys {@code messaging-history.set}.
     * <p>Not yet dispatched by JaWaClient: wa-java has no history-sync
     * message path producing chats/messages/contacts together. App-state
     * patches are <em>not</em> emitted as history sets.
     */
    default void onMessagingHistorySet(MessagingHistorySet set) {}

    /**
     * Baileys {@code messaging-history.status}.
     * <p>Not yet dispatched by JaWaClient.
     */
    default void onMessagingHistoryStatus(MessagingHistoryStatus status) {}

    /**
     * Baileys {@code chats.upsert}.
     * <p>Dispatched by JaWaClient: first message in a new chat (via the
     * in-memory store) and group-create notifications; and by
     * {@code InMemoryStore} when bound and a chat is created.
     */
    default void onChatsUpsert(ChatsUpsert upsert) {}

    /**
     * Baileys {@code chats.update}.
     * <p>Dispatched by JaWaClient: later messages in a known chat (via the
     * in-memory store); and by {@code InMemoryStore} when bound on
     * message upserts and flag changes.
     */
    default void onChatsUpdate(ChatsUpdate update) {}

    /**
     * Baileys {@code chats.delete}.
     * <p>Dispatched by {@code InMemoryStore} when bound and a chat is
     * deleted. JaWaClient has no inbound chat-delete stanza path, so it
     * does not dispatch this itself.
     */
    default void onChatsDelete(ChatsDelete delete) {}

    /**
     * Baileys {@code chats.lock}.
     * <p>Not yet dispatched by JaWaClient.
     */
    default void onChatsLock(ChatsLock lock) {}

    /**
     * Baileys {@code lid-mapping.update}.
     * <p>Dispatched by JaWaClient: {@code sender_pn}/{@code participant_pn}
     * mappings on inbound messages, and mappings newly learned by
     * {@code resolveLidToPn}. The store records the same mappings
     * change-only when bound.
     */
    default void onLidMappingUpdate(LidMappingUpdate update) {}

    /**
     * Baileys {@code presence.update}.
     * <p>Dispatched by JaWaClient from inbound presence / chat-presence
     * stanzas, parsed by {@link PresenceEventParser}.
     */
    default void onPresenceUpdate(PresenceUpdate update) {}

    /**
     * Baileys {@code contacts.upsert}.
     * <p>Dispatched by {@code InMemoryStore} when bound and a new contact
     * is upserted. JaWaClient has no inbound contact-sync path, so it
     * does not dispatch this itself.
     */
    default void onContactsUpsert(ContactsUpsert upsert) {}

    /**
     * Baileys {@code contacts.update}.
     * <p>Dispatched by JaWaClient for picture notifications (imgUrl
     * {@code changed}/{@code removed}); and by {@code InMemoryStore}
     * when bound on contact updates and picture changes.
     */
    default void onContactsUpdate(ContactsUpdate update) {}

    /**
     * Baileys {@code messages.upsert}.
     * <p>Dispatched by JaWaClient at the same point as the legacy
     * {@code Listener.onMessage}, wrapping the same decoded message in a
     * single-element list with type {@code "notify"}.
     */
    default void onMessageUpsert(MessageUpsert upsert) {}

    /**
     * Baileys {@code messages.update}.
     * <p>Dispatched by JaWaClient for inbound edit and revoke protocol
     * messages, as key-only updates (target key + timestamp; edited
     * content is not modelled in the Increment 1 payload).
     */
    default void onMessagesUpdate(MessagesUpdate update) {}

    /**
     * Baileys {@code messages.delete}.
     * <p>Dispatched by {@code InMemoryStore} when bound and a stored
     * message is deleted. No inbound delete stanza path exists in
     * JaWaClient (Baileys deletes arrive via app-state sync actions,
     * which wa-java does not yet map to events).
     */
    default void onMessagesDelete(MessagesDelete delete) {}

    /**
     * Baileys {@code messages.media-update}.
     * <p>Dispatched by JaWaClient from {@code mediaretry} notifications
     * (see {@link MediaRetryParser}). The encrypted media payload Baileys
     * also carries is not modelled in the payload.
     */
    default void onMessagesMediaUpdate(MessagesMediaUpdate update) {}

    /**
     * Baileys {@code messages.reaction}.
     * <p>Dispatched by JaWaClient for inbound reaction messages, in
     * addition to the existing {@code messages.upsert} delivery of the
     * raw reaction message (legacy behaviour preserved).
     */
    default void onMessagesReaction(MessagesReaction reaction) {}

    /**
     * Baileys {@code message-receipt.update}.
     * <p>Dispatched by JaWaClient at the same receipt dispatch point as the
     * legacy {@code Listener.onReceipt}.
     */
    default void onMessageReceiptUpdate(MessageReceiptUpdate update) {}

    /**
     * Baileys {@code groups.upsert}.
     * <p>Dispatched by JaWaClient for group-create notifications
     * ({@code w:gp2} {@code create}), alongside {@code chats.upsert}.
     */
    default void onGroupsUpsert(GroupsUpsert upsert) {}

    /**
     * Baileys {@code groups.update}.
     * <p>Dispatched by JaWaClient for {@code w:gp2} group-change
     * notifications (subject, description, announce, restrict, invite
     * code, member-add mode, join-approval mode).
     */
    default void onGroupsUpdate(GroupsUpdate update) {}

    /**
     * Baileys {@code group-participants.update}.
     * <p>Dispatched by JaWaClient for {@code w:gp2} add/remove/promote/
     * demote/leave/modify notifications ({@code leave} is emitted as
     * {@code remove}, matching Baileys' emitted action set).
     */
    default void onGroupParticipantsUpdate(GroupParticipantsUpdate update) {}

    /**
     * Baileys {@code group.join-request}.
     * <p>Dispatched by JaWaClient for {@code w:gp2}
     * created/revoked membership-request notifications.
     */
    default void onGroupJoinRequest(GroupJoinRequest request) {}

    /**
     * Baileys {@code group.member-tag.update}.
     * <p>Dispatched by JaWaClient for inbound
     * {@code GROUP_MEMBER_LABEL_CHANGE} protocol messages.
     */
    default void onGroupMemberTagUpdate(GroupMemberTagUpdate update) {}

    /**
     * Baileys {@code blocklist.update}.
     * <p>Dispatched by JaWaClient for {@code account_sync} blocklist
     * notifications (one event per item) and for successful local
     * block/unblock calls.
     */
    default void onBlocklistUpdate(BlocklistUpdate update) {}

    /**
     * Baileys {@code call}.
     * <p>Dispatched by JaWaClient from inbound {@code call} stanzas, parsed
     * by {@link CallEventParser}. Note the documented difference from
     * Baileys: Baileys acks the call node after handling (including on
     * error); the new JaWaClient branch does <em>not</em> ack call stanzas
     * (the existing notification/receipt ack path is unchanged).
     */
    default void onCall(CallEvent call) {}

    /**
     * Baileys {@code labels.edit}.
     * <p>Dispatched by {@code InMemoryStore} when bound and a label is
     * upserted or deleted (20-label cap, as in Baileys). JaWaClient has
     * no label sync path, so it does not dispatch this itself.
     */
    default void onLabelsEdit(LabelsEdit edit) {}

    /**
     * Baileys {@code labels.association}.
     * <p>Dispatched by {@code InMemoryStore} when bound on association
     * add/remove. JaWaClient has no label sync path.
     */
    default void onLabelsAssociation(LabelsAssociation association) {}

    /**
     * Baileys {@code newsletter.reaction}.
     * <p>Dispatched by JaWaClient for newsletter notifications with a
     * {@code reaction} child.
     */
    default void onNewsletterReaction(NewsletterReaction reaction) {}

    /**
     * Baileys {@code newsletter.view}.
     * <p>Dispatched by JaWaClient for newsletter notifications with a
     * {@code view} child.
     */
    default void onNewsletterView(NewsletterView view) {}

    /**
     * Baileys {@code newsletter-participants.update}.
     * <p>Dispatched by JaWaClient for newsletter notifications with a
     * {@code participant} child. The mex admin-promote path is not yet
     * wired.
     */
    default void onNewsletterParticipantsUpdate(NewsletterParticipantsUpdate update) {}

    /**
     * Baileys {@code newsletter-settings.update}.
     * <p>Dispatched by JaWaClient for newsletter notifications with an
     * {@code update/settings} child (name/description only; the verbatim
     * mex settings object is not modelled).
     */
    default void onNewsletterSettingsUpdate(NewsletterSettingsUpdate update) {}

    /**
     * Baileys {@code settings.update}.
     * <p>Not yet dispatched by JaWaClient.
     */
    default void onSettingsUpdate(SettingsUpdate update) {}

    /**
     * Baileys {@code message-capping.update}.
     * <p>Not yet dispatched by JaWaClient.
     */
    default void onMessageCappingUpdate(MessageCappingUpdate update) {}

    /**
     * Generic fallback, invoked by {@link WaEventBus} for every dispatched
     * event after its typed method. Implement this alone if one method for
     * all events is preferred; it is not a second delivery channel and the
     * bus does not re-emit events through {@link BaileysEventType#EVENT}.
     *
     * @param type event type
     * @param payload event payload (the record for {@code type})
     */
    default void onEvent(BaileysEventType type, Object payload) {}
}
