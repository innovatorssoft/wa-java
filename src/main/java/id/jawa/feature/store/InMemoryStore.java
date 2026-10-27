package id.jawa.feature.store;

import id.jawa.event.BaileysEventType;
import id.jawa.event.BlocklistUpdate;
import id.jawa.event.ChatsDelete;
import id.jawa.event.ChatsUpdate;
import id.jawa.event.ChatsUpsert;
import id.jawa.event.ContactsUpdate;
import id.jawa.event.ContactsUpsert;
import id.jawa.event.GroupsUpdate;
import id.jawa.event.GroupsUpsert;
import id.jawa.event.LabelsAssociation;
import id.jawa.event.LabelsEdit;
import id.jawa.event.LidMappingUpdate;
import id.jawa.event.MessagesDelete;
import id.jawa.event.MessagesReaction;
import id.jawa.event.PresenceInfo;
import id.jawa.event.PresenceUpdate;
import id.jawa.event.WaEventBus;
import id.jawa.feature.messaging.MessageReceiver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory chat/contact/message/group store: the wa-java equivalent of
 * Baileys' {@code makeInMemoryStore}.
 * <p>
 * Baileys' store is an event-driven cache that subscribes to the socket's
 * event bus and keeps chats, contacts, messages, groups and labels in
 * memory. This store inverts that wiring to fit wa-java, where there is no
 * socket-level event subscription point for the store: mutation methods are
 * called by the client (see {@code JaWaClient}, which feeds inbound messages
 * here) and, when a {@link WaEventBus} is bound via {@link #bind(WaEventBus)},
 * each mutation dispatches the matching Baileys event through that bus.
 * With no bus bound the store still works — it just emits nothing.
 * <p>
 * Semantics, matching Baileys as far as wa-java's models allow:
 * <ul>
 *   <li>Chats are keyed by chat JID. The first message in an unknown chat
 *       creates it and emits {@code chats.upsert}; later messages update it
 *       and emit {@code chats.update}. {@code chats.delete} removes the chat
 *       and all its messages.</li>
 *   <li>Messages are kept per chat in arrival order. Each chat's history is
 *       capped (default {@link #DEFAULT_MAX_MESSAGES_PER_CHAT}) so a long
 *       session cannot grow memory without bound; the oldest messages are
 *       dropped first. This cap is a wa-java adaptation — Baileys' store
 *       keeps messages until the caller evicts them — and is documented here
 *       rather than hidden.</li>
 *   <li>Unread counts count inbound messages only: the store cannot know
 *       whether a message is from the account, because
 *       {@link MessageReceiver.Decoded} carries no {@code fromMe} flag, so
 *       {@link #upsertMessage(MessageReceiver.Decoded, boolean)} takes it
 *       explicitly and {@link #upsertMessage(MessageReceiver.Decoded)}
 *       counts every message as unread.</li>
 *   <li>Contacts and groups are plain maps keyed by JID, upserted and
 *       updated with the same event payloads the Increment 1 event package
 *       defines. Labels and label associations are stored with Baileys'
 *       20-label cap; there is still no label <em>API</em> (no server
 *       round-trip), only the in-memory state the store can maintain from
 *       events and direct calls.</li>
 *   <li>Presences are merged per chat and participant, and group
 *       participant lists are maintained for add/remove, matching the
 *       Baileys store's subscribed behaviour (it does not subscribe to
 *       {@code groups.upsert} events — group metadata here is stored via
 *       explicit {@link #upsertGroup} calls).</li>
 * </ul>
 * <p>
 * All methods are thread-safe (concurrent maps plus per-chat locks for the
 * message deque). Returned lists are snapshots.
 */
public final class InMemoryStore {

    /** Default per-chat message cap, see the class Javadoc. */
    public static final int DEFAULT_MAX_MESSAGES_PER_CHAT = 500;

    /** One stored chat: id plus the fields the store actually maintains. */
    public record StoredChat(String id, long lastMessageTimestamp, int unreadCount,
                             boolean pinned, boolean archived) {}

    private record ChatState(String id, long lastMessageTimestamp, int unreadCount,
                             boolean pinned, boolean archived) {}

    private final int maxMessagesPerChat;
    private final ConcurrentHashMap<String, ChatState> chats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Deque<MessageReceiver.Decoded>> messages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> chatLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ContactsUpsert.Contact> contacts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GroupsUpsert.GroupInfo> groups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lidToPn = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> reactions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, PresenceInfo>> presences =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LabelsEdit> labels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LabelsAssociation> labelAssociations = new ConcurrentHashMap<>();
    private volatile String connectionState = "close";
    private volatile WaEventBus eventBus;

    /** Creates a store with {@link #DEFAULT_MAX_MESSAGES_PER_CHAT}. */
    public InMemoryStore() {
        this(DEFAULT_MAX_MESSAGES_PER_CHAT);
    }

    /**
     * Creates a store with an explicit per-chat message cap.
     *
     * @param maxMessagesPerChat cap per chat, must be positive
     */
    public InMemoryStore(int maxMessagesPerChat) {
        if (maxMessagesPerChat <= 0) {
            throw new IllegalArgumentException("maxMessagesPerChat must be positive");
        }
        this.maxMessagesPerChat = maxMessagesPerChat;
    }

    /** @return the per-chat message cap this store was created with */
    public int maxMessagesPerChat() {
        return maxMessagesPerChat;
    }

    /**
     * Binds an event bus: subsequent mutations dispatch Baileys events
     * through it. Passing null unbinds.
     *
     * @param bus bus to dispatch through, or null to stop dispatching
     * @return this store, for chaining
     */
    public InMemoryStore bind(WaEventBus bus) {
        this.eventBus = bus;
        return this;
    }

    private void dispatch(BaileysEventType type, Object payload) {
        WaEventBus bus = eventBus;
        if (bus != null) bus.dispatch(type, payload);
    }

    private static String chatIdFor(MessageReceiver.Decoded decoded) {
        if (decoded.groupJid() != null) return decoded.groupJid();
        return decoded.senderJid();
    }

    /**
     * Stores an inbound message, creating or updating its chat. Counts the
     * message as unread.
     *
     * @param decoded decoded inbound message; null is ignored
     * @return the chat id the message was stored under, or null for null input
     */
    public String upsertMessage(MessageReceiver.Decoded decoded) {
        return upsertMessage(decoded, false);
    }

    /**
     * Stores an inbound message, creating or updating its chat.
     *
     * @param decoded decoded inbound message; null is ignored
     * @param fromMe whether the message is from the account; own messages do
     *               not raise the unread count
     * @return the chat id the message was stored under, or null for null input
     */
    public String upsertMessage(MessageReceiver.Decoded decoded, boolean fromMe) {
        if (decoded == null) return null;
        String chatId = chatIdFor(decoded);
        if (chatId == null) return null;
        Object lock = chatLocks.computeIfAbsent(chatId, k -> new Object());
        boolean isNew;
        synchronized (lock) {
            ChatState prev = chats.get(chatId);
            isNew = prev == null;
            long ts = decoded.timestamp() > 0 ? decoded.timestamp()
                : (prev == null ? 0L : prev.lastMessageTimestamp());
            int unread = (prev == null ? 0 : prev.unreadCount()) + (fromMe ? 0 : 1);
            chats.put(chatId, new ChatState(chatId, ts, unread,
                prev != null && prev.pinned(), prev != null && prev.archived()));
            Deque<MessageReceiver.Decoded> deque =
                messages.computeIfAbsent(chatId, k -> new ArrayDeque<>());
            deque.addLast(decoded);
            while (deque.size() > maxMessagesPerChat) deque.removeFirst();
        }
        if (isNew) {
            dispatch(BaileysEventType.CHATS_UPSERT, new ChatsUpsert(List.of(chatId)));
        } else {
            dispatch(BaileysEventType.CHATS_UPDATE, new ChatsUpdate(List.of(chatId)));
        }
        return chatId;
    }

    /**
     * Stores a message under an explicit chat id (for messages whose chat
     * cannot be derived from the decoded form, e.g. outgoing messages the
     * caller already addressed).
     *
     * @param chatId chat JID
     * @param decoded message to store
     * @param fromMe whether the message is from the account
     * @return true if stored, false for null arguments
     */
    public boolean upsertMessage(String chatId, MessageReceiver.Decoded decoded, boolean fromMe) {
        if (chatId == null || decoded == null) return false;
        Object lock = chatLocks.computeIfAbsent(chatId, k -> new Object());
        boolean isNew;
        synchronized (lock) {
            ChatState prev = chats.get(chatId);
            isNew = prev == null;
            long ts = decoded.timestamp() > 0 ? decoded.timestamp()
                : (prev == null ? 0L : prev.lastMessageTimestamp());
            int unread = (prev == null ? 0 : prev.unreadCount()) + (fromMe ? 0 : 1);
            chats.put(chatId, new ChatState(chatId, ts, unread,
                prev != null && prev.pinned(), prev != null && prev.archived()));
            Deque<MessageReceiver.Decoded> deque =
                messages.computeIfAbsent(chatId, k -> new ArrayDeque<>());
            deque.addLast(decoded);
            while (deque.size() > maxMessagesPerChat) deque.removeFirst();
        }
        if (isNew) {
            dispatch(BaileysEventType.CHATS_UPSERT, new ChatsUpsert(List.of(chatId)));
        } else {
            dispatch(BaileysEventType.CHATS_UPDATE, new ChatsUpdate(List.of(chatId)));
        }
        return true;
    }

    /** @return stored chat for the id, or null */
    public StoredChat getChat(String chatId) {
        ChatState s = chatId == null ? null : chats.get(chatId);
        if (s == null) return null;
        return new StoredChat(s.id(), s.lastMessageTimestamp(), s.unreadCount(), s.pinned(), s.archived());
    }

    /**
     * @return all chats in Baileys {@code KeyedDB} order: pinned first, then
     *         unarchived before archived, then most recently active first
     *         (by last message timestamp, ties broken by chat id)
     */
    public List<StoredChat> chats() {
        List<StoredChat> out = new ArrayList<>();
        for (ChatState s : chats.values()) {
            out.add(new StoredChat(s.id(), s.lastMessageTimestamp(), s.unreadCount(), s.pinned(), s.archived()));
        }
        out.sort(Comparator.comparing(StoredChat::pinned).reversed()
            .thenComparing(StoredChat::archived)
            .thenComparing(Comparator.comparingLong(StoredChat::lastMessageTimestamp).reversed())
            .thenComparing(StoredChat::id));
        return out;
    }

    /**
     * Creates a chat entry if none exists, without storing a message.
     * Used for chats that appear without a message (e.g. group-create
     * notifications). Emits {@code chats.upsert} only when a chat is
     * actually created.
     *
     * @param chatId chat JID
     * @param lastMessageTimestamp conversation timestamp, 0 when unknown
     * @return true if a chat was created, false if it already existed
     */
    public boolean ensureChat(String chatId, long lastMessageTimestamp) {
        if (chatId == null) return false;
        ChatState prev = chats.putIfAbsent(chatId,
            new ChatState(chatId, lastMessageTimestamp, 0, false, false));
        if (prev != null) return false;
        dispatch(BaileysEventType.CHATS_UPSERT, new ChatsUpsert(List.of(chatId)));
        return true;
    }

    /** @return Baileys-store connection state ({@code close} by default) */
    public String connectionState() {
        return connectionState;
    }

    /**
     * Records a connection-state change (Baileys' store merges
     * {@code connection.update} into its state). No event is emitted here:
     * the client dispatches {@code connection.update} itself.
     *
     * @param state new state string, e.g. {@code open}, {@code close}
     */
    public void setConnectionState(String state) {
        if (state != null) connectionState = state;
    }

    /** @return number of stored chats */
    public int chatCount() {
        return chats.size();
    }

    /**
     * Marks a chat read: unread count reset to zero. No event is emitted —
     * Baileys has no read event for a local mark-read.
     *
     * @param chatId chat JID
     * @return true if the chat exists
     */
    public boolean markChatRead(String chatId) {
        if (chatId == null) return false;
        return chats.computeIfPresent(chatId, (k, prev) ->
            new ChatState(prev.id(), prev.lastMessageTimestamp(), 0, prev.pinned(), prev.archived())) != null;
    }

    /**
     * Sets a chat's pin/archive flags, emitting {@code chats.update}.
     *
     * @param chatId chat JID
     * @param pinned new pin flag
     * @param archived new archive flag
     * @return true if the chat exists
     */
    public boolean setChatFlags(String chatId, boolean pinned, boolean archived) {
        if (chatId == null) return false;
        ChatState updated = chats.computeIfPresent(chatId, (k, prev) ->
            new ChatState(prev.id(), prev.lastMessageTimestamp(), prev.unreadCount(), pinned, archived));
        if (updated == null) return false;
        dispatch(BaileysEventType.CHATS_UPDATE, new ChatsUpdate(List.of(chatId)));
        return true;
    }

    /**
     * Deletes a chat and all its stored messages, emitting
     * {@code chats.delete}. Unknown chats are a no-op returning false.
     *
     * @param chatId chat JID
     * @return true if a chat was deleted
     */
    public boolean deleteChat(String chatId) {
        if (chatId == null) return false;
        ChatState removed = chats.remove(chatId);
        messages.remove(chatId);
        chatLocks.remove(chatId);
        if (removed == null) return false;
        dispatch(BaileysEventType.CHATS_DELETE, new ChatsDelete(List.of(chatId)));
        return true;
    }

    /**
     * Clears a chat's stored messages without deleting the chat itself
     * (Baileys {@code clear} semantics). No event is emitted: Baileys emits
     * no event for a local clear.
     *
     * @param chatId chat JID
     * @return true if the chat exists
     */
    public boolean clearChat(String chatId) {
        if (chatId == null || !chats.containsKey(chatId)) return false;
        Object lock = chatLocks.computeIfAbsent(chatId, k -> new Object());
        synchronized (lock) {
            Deque<MessageReceiver.Decoded> deque = messages.get(chatId);
            if (deque != null) deque.clear();
        }
        return true;
    }

    /**
     * @param chatId chat JID
     * @return stored messages for the chat in arrival order, oldest first
     */
    public List<MessageReceiver.Decoded> messages(String chatId) {
        if (chatId == null) return List.of();
        Deque<MessageReceiver.Decoded> deque = messages.get(chatId);
        if (deque == null) return List.of();
        Object lock = chatLocks.computeIfAbsent(chatId, k -> new Object());
        synchronized (lock) {
            return List.copyOf(deque);
        }
    }

    /**
     * Baileys {@code fetchMessageHistory}-style query: the newest messages
     * of a chat, newest first, limited to {@code limit}.
     *
     * @param chatId chat JID
     * @param limit maximum messages to return; non-positive returns empty
     * @return newest messages first, at most {@code limit}
     */
    public List<MessageReceiver.Decoded> latestMessages(String chatId, int limit) {
        if (chatId == null || limit <= 0) return List.of();
        List<MessageReceiver.Decoded> all = messages(chatId);
        int from = Math.max(0, all.size() - limit);
        List<MessageReceiver.Decoded> slice = new ArrayList<>(all.subList(from, all.size()));
        java.util.Collections.reverse(slice);
        return slice;
    }

    /**
     * @param chatId chat JID
     * @param messageId message id
     * @return the stored message, or null
     */
    public MessageReceiver.Decoded getMessage(String chatId, String messageId) {
        if (chatId == null || messageId == null) return null;
        for (MessageReceiver.Decoded d : messages(chatId)) {
            if (messageId.equals(d.msgId())) return d;
        }
        return null;
    }

    /**
     * Deletes one stored message, emitting {@code messages.delete} with its
     * key. The chat itself is kept even when its last message is deleted.
     *
     * @param chatId chat JID
     * @param messageId message id
     * @param fromMe whether the deleted message was from the account
     * @param participant participant JID in groups, null otherwise
     * @return true if a message was deleted
     */
    public boolean deleteMessage(String chatId, String messageId, boolean fromMe, String participant) {
        if (chatId == null || messageId == null) return false;
        Object lock = chatLocks.computeIfAbsent(chatId, k -> new Object());
        boolean removed;
        synchronized (lock) {
            Deque<MessageReceiver.Decoded> deque = messages.get(chatId);
            removed = deque != null && deque.removeIf(d -> messageId.equals(d.msgId()));
        }
        if (!removed) return false;
        dispatch(BaileysEventType.MESSAGES_DELETE, new MessagesDelete(List.of(
            new MessagesDelete.MessageKey(messageId, chatId, fromMe, participant))));
        return true;
    }

    /**
     * Applies a reaction to a stored message, emitting
     * {@code messages.reaction}. The reaction is recorded even if the target
     * message is not stored — Baileys emits reactions for keys it may not
     * hold — but the return value reports whether the message was found.
     *
     * @param chatId chat JID
     * @param messageId message id
     * @param fromMe whether the reacted-to message was from the account
     * @param participant participant JID, null otherwise
     * @param reactionCode emoji/text code, empty removes the reaction
     * @param senderJid reacting user JID, may be null
     * @return true if the target message is stored
     */
    public boolean applyReaction(String chatId, String messageId, boolean fromMe, String participant,
                                 String reactionCode, String senderJid) {
        if (chatId == null || messageId == null) return false;
        String code = reactionCode == null ? "" : reactionCode;
        if (code.isEmpty()) {
            reactions.remove(chatId + "\0" + messageId);
        } else {
            reactions.put(chatId + "\0" + messageId, code);
        }
        dispatch(BaileysEventType.MESSAGES_REACTION, new MessagesReaction(List.of(
            new MessagesReaction.MessageReaction(messageId, chatId, fromMe, participant, code, senderJid))));
        return getMessage(chatId, messageId) != null;
    }

    /** @return current reaction code for a message, or null if none */
    public String reactionFor(String chatId, String messageId) {
        if (chatId == null || messageId == null) return null;
        return reactions.get(chatId + "\0" + messageId);
    }

    /**
     * Upserts a contact, emitting {@code contacts.upsert} for a new contact
     * and {@code contacts.update} for an existing one (with the notify name
     * carried as a partial update).
     *
     * @param contact contact to store; null is ignored
     * @return true if stored
     */
    public boolean upsertContact(ContactsUpsert.Contact contact) {
        if (contact == null || contact.id() == null) return false;
        boolean isNew = !contacts.containsKey(contact.id());
        contacts.put(contact.id(), contact);
        if (isNew) {
            dispatch(BaileysEventType.CONTACTS_UPSERT, new ContactsUpsert(List.of(contact)));
        } else {
            dispatch(BaileysEventType.CONTACTS_UPDATE, new ContactsUpdate(List.of(
                new ContactsUpdate.ContactUpdate(contact.id(), contact.name(), null, null))));
        }
        return true;
    }

    /** @return stored contact for the JID, or null */
    public ContactsUpsert.Contact getContact(String jid) {
        return jid == null ? null : contacts.get(jid);
    }

    /**
     * Caller-side picture-URL cache, mirroring the cache half of
     * Baileys' store {@code fetchImageUrl(jid, sock)} (in
     * {@code lib/Store/make-in-memory-store.js}): Baileys returns the
     * stored {@code imgUrl} when present and otherwise fetches it
     * through the socket ({@code profilePictureUrl}) and caches it.
     * This store has no transport, so it cannot do the fetch half:
     * this method returns the cached {@code imgUrl} when a contact is
     * stored and has one, and null otherwise (unknown contact, or a
     * contact whose URL was cleared / never set). Fetching a missing
     * URL is the caller's job — e.g. via the profile-picture query —
     * after which the caller stores it with
     * {@link #upsertContact(ContactsUpsert.Contact)}.
     *
     * @param jid contact JID
     * @return cached picture URL, or null when absent
     */
    public String cachedContactImgUrl(String jid) {
        ContactsUpsert.Contact contact = getContact(jid);
        return contact == null ? null : contact.imgUrl();
    }

    /** @return all stored contacts, sorted by JID for determinism */
    public List<ContactsUpsert.Contact> contacts() {
        List<ContactsUpsert.Contact> out = new ArrayList<>(contacts.values());
        out.sort(Comparator.comparing(ContactsUpsert.Contact::id));
        return out;
    }

    /** @return number of stored contacts */
    public int contactCount() {
        return contacts.size();
    }

    /**
     * Upserts group metadata, emitting {@code groups.upsert} for a new group
     * and {@code groups.update} for an existing one.
     *
     * @param group group metadata; null is ignored
     * @return true if stored
     */
    public boolean upsertGroup(GroupsUpsert.GroupInfo group) {
        if (group == null || group.id() == null) return false;
        boolean isNew = !groups.containsKey(group.id());
        groups.put(group.id(), group);
        if (isNew) {
            dispatch(BaileysEventType.GROUPS_UPSERT, new GroupsUpsert(List.of(group)));
        } else {
            dispatch(BaileysEventType.GROUPS_UPDATE, new GroupsUpdate(List.of(
                new GroupsUpdate.GroupUpdate(group.id(), group.subject(),
                    group.author(), group.authorPn(), group.authorUsername()))));
        }
        return true;
    }

    /** @return stored group metadata for the JID, or null */
    public GroupsUpsert.GroupInfo getGroup(String jid) {
        return jid == null ? null : groups.get(jid);
    }

    /** @return all stored groups, sorted by JID for determinism */
    public List<GroupsUpsert.GroupInfo> groups() {
        List<GroupsUpsert.GroupInfo> out = new ArrayList<>(groups.values());
        out.sort(Comparator.comparing(GroupsUpsert.GroupInfo::id));
        return out;
    }

    /**
     * Records a LID→phone-number mapping, emitting
     * {@code lid-mapping.update} only when the mapping is new or changed
     * (unchanged re-registrations are silent, matching the Baileys store's
     * change-driven behaviour).
     *
     * @param lid linked-identity JID
     * @param pn phone-number JID
     * @return true if the mapping was newly stored or changed
     */
    public boolean upsertLidMapping(String lid, String pn) {
        if (lid == null || pn == null) return false;
        String prev = lidToPn.put(lid, pn);
        if (pn.equals(prev)) return false;
        dispatch(BaileysEventType.LID_MAPPING_UPDATE, new LidMappingUpdate(lid, pn));
        return true;
    }

    /** @return phone-number JID for a LID, or null */
    public String pnForLid(String lid) {
        return lid == null ? null : lidToPn.get(lid);
    }

    /**
     * Records a blocklist change, emitting one {@code blocklist.update} per
     * call with a single-element blocklist — Baileys emits one event per
     * JID, not one event for the whole list.
     *
     * @param jid affected JID
     * @param blocked true for a block ({@code add}), false for unblock
     */
    public void recordBlocklistChange(String jid, boolean blocked) {
        if (jid == null) return;
        dispatch(BaileysEventType.BLOCKLIST_UPDATE,
            new BlocklistUpdate(List.of(jid), blocked ? "add" : "remove"));
    }

    /**
     * Baileys {@code loadMessages(jid, count, cursor)}: messages
     * <em>before</em> the cursor message (exclusive), or the whole history
     * when the cursor is null, limited to the last {@code count} of that
     * range, in arrival order. There is deliberately no forward pagination:
     * Baileys' {@code 'after'} branch always returns an empty list.
     *
     * @param chatId chat JID
     * @param count maximum messages to return; non-positive returns empty
     * @param cursorMessageId message id to page before, or null for the latest
     * @return messages in arrival order, at most {@code count}
     */
    public List<MessageReceiver.Decoded> loadMessages(String chatId, int count, String cursorMessageId) {
        if (chatId == null || count <= 0) return List.of();
        List<MessageReceiver.Decoded> all = messages(chatId);
        int end = all.size();
        if (cursorMessageId != null) {
            end = all.size();
            for (int i = 0; i < all.size(); i++) {
                if (cursorMessageId.equals(all.get(i).msgId())) {
                    end = i;
                    break;
                }
            }
        }
        int from = Math.max(0, end - count);
        return new ArrayList<>(all.subList(from, end));
    }

    /**
     * Merges a presence update into the store (Baileys' store keeps
     * {@code presences[id][participant]}). No event is emitted: the client
     * dispatches {@code presence.update} itself.
     *
     * @param update presence update; null is ignored
     */
    public void upsertPresence(PresenceUpdate update) {
        if (update == null || update.id() == null || update.presences() == null) return;
        ConcurrentHashMap<String, PresenceInfo> perChat =
            presences.computeIfAbsent(update.id(), k -> new ConcurrentHashMap<>());
        perChat.putAll(update.presences());
    }

    /** @return chat ids that have any stored presence, sorted for determinism */
    public List<String> presenceChatIds() {
        List<String> out = new ArrayList<>(presences.keySet());
        out.sort(String::compareTo);
        return out;
    }

    /** @return stored presences for a chat id, participant JID → presence, or empty */
    public Map<String, PresenceInfo> presences(String chatId) {
        if (chatId == null) return Map.of();
        ConcurrentHashMap<String, PresenceInfo> perChat = presences.get(chatId);
        return perChat == null ? Map.of() : Map.copyOf(perChat);
    }

    /**
     * Upserts a label, emitting {@code labels.edit}. A deleted label is
     * removed instead. New labels beyond Baileys' cap of 20 are rejected
     * (returning false, like Baileys' error log) rather than stored.
     *
     * @param label label edit payload; null is ignored
     * @return true if stored or removed, false if rejected by the cap
     */
    public boolean upsertLabel(LabelsEdit label) {
        if (label == null || label.id() == null) return false;
        if (label.deleted()) {
            boolean removed = labels.remove(label.id()) != null;
            dispatch(BaileysEventType.LABELS_EDIT, label);
            return removed;
        }
        if (!labels.containsKey(label.id()) && labels.size() >= 20) return false;
        labels.put(label.id(), label);
        dispatch(BaileysEventType.LABELS_EDIT, label);
        return true;
    }

    /** @return stored label for the id, or null */
    public LabelsEdit getLabel(String labelId) {
        return labelId == null ? null : labels.get(labelId);
    }

    /** @return all stored labels, sorted by id */
    public List<LabelsEdit> labels() {
        List<LabelsEdit> out = new ArrayList<>(labels.values());
        out.sort(Comparator.comparing(LabelsEdit::id));
        return out;
    }

    /**
     * Records a label association ({@code add}) or removes one
     * ({@code remove}), emitting {@code labels.association}. Unknown types
     * are ignored, matching Baileys' error path.
     *
     * @param association association payload; null is ignored
     * @return true if recorded or removed
     */
    public boolean applyLabelAssociation(LabelsAssociation association) {
        if (association == null) return false;
        // The key excludes the add/remove verb: Baileys stores the inner
        // association object, so a remove must match the key an add stored.
        String key = association.associationType() + "\0"
            + association.chatId() + "\0" + association.labelId() + "\0" + association.messageId();
        if ("add".equals(association.type())) {
            labelAssociations.put(key, association);
        } else if ("remove".equals(association.type())) {
            labelAssociations.remove(key);
        } else {
            return false;
        }
        dispatch(BaileysEventType.LABELS_ASSOCIATION, association);
        return true;
    }

    /** @return label associations for a chat, sorted deterministically */
    public List<LabelsAssociation> chatLabels(String chatId) {
        if (chatId == null) return List.of();
        List<LabelsAssociation> out = new ArrayList<>();
        for (LabelsAssociation a : labelAssociations.values()) {
            if (chatId.equals(a.chatId())) out.add(a);
        }
        out.sort(Comparator.comparing(LabelsAssociation::labelId)
            .thenComparing(a -> a.messageId() == null ? "" : a.messageId()));
        return out;
    }

    /** @return label ids associated with a message */
    public List<String> messageLabels(String messageId) {
        if (messageId == null) return List.of();
        List<String> out = new ArrayList<>();
        for (LabelsAssociation a : labelAssociations.values()) {
            if (messageId.equals(a.messageId())) out.add(a.labelId());
        }
        out.sort(String::compareTo);
        return out;
    }

    /**
     * Applies a group-participant change to a stored group (Baileys' store
     * behaviour): {@code add} unions participants, {@code remove} filters
     * them out; {@code promote}, {@code demote} and {@code modify} do not
     * change membership (Baileys only updates the admin flag, which wa-java's
     * group record does not carry). Unknown groups are silently skipped,
     * like Baileys' store.
     *
     * @param groupId group JID
     * @param participants affected participant JIDs
     * @param action Baileys action string
     * @return true if the group is stored
     */
    public boolean updateGroupParticipants(String groupId, List<String> participants, String action) {
        if (groupId == null || participants == null || action == null) return false;
        GroupsUpsert.GroupInfo current = groups.get(groupId);
        if (current == null) return false;
        List<String> updated = new ArrayList<>(current.participantJids());
        if ("add".equals(action)) {
            for (String p : participants) {
                if (p != null && !updated.contains(p)) updated.add(p);
            }
        } else if ("remove".equals(action)) {
            updated.removeAll(participants);
        } else {
            return true;
        }
        groups.put(groupId, new GroupsUpsert.GroupInfo(current.id(), current.subject(),
            current.author(), current.authorPn(), current.authorUsername(), updated));
        return true;
    }

    /**
     * Applies a picture notification to a stored contact, emitting
     * {@code contacts.update} with {@code imgUrl} set to {@code changed} or
     * {@code removed}. The stored picture URL is <em>not</em> set to that
     * marker: {@code changed} keeps the old URL (a refetch is the caller's
     * job, as in Baileys' store) and {@code removed} clears it. Unknown
     * contacts are skipped: Baileys' store likewise only updates contacts
     * it already holds.
     *
     * @param jid contact JID
     * @param imgUrl {@code changed} or {@code removed}
     * @return true if the contact is stored and was updated
     */
    public boolean updateContactImgUrl(String jid, String imgUrl) {
        if (jid == null || imgUrl == null) return false;
        ContactsUpsert.Contact current = contacts.get(jid);
        if (current == null) return false;
        String storedUrl = "removed".equals(imgUrl) ? null : current.imgUrl();
        contacts.put(jid, new ContactsUpsert.Contact(current.id(), current.name(), current.username(),
            current.lid(), current.phoneNumber(), storedUrl));
        dispatch(BaileysEventType.CONTACTS_UPDATE, new ContactsUpdate(List.of(
            new ContactsUpdate.ContactUpdate(jid, null, null, imgUrl))));
        return true;
    }

    /** @return unmodifiable snapshot of the store's chat-id → message-count map, for diagnostics */
    public Map<String, Integer> messageCounts() {
        Map<String, Integer> out = new ConcurrentHashMap<>();
        for (Map.Entry<String, Deque<MessageReceiver.Decoded>> e : messages.entrySet()) {
            out.put(e.getKey(), e.getValue().size());
        }
        return Map.copyOf(out);
    }

    /** One stored reaction, for snapshots (see {@link #reactions()}). */
    public record ReactionEntry(String chatId, String messageId, String code) {}

    /** @return all stored reactions, sorted by chat then message id */
    public List<ReactionEntry> reactions() {
        List<ReactionEntry> out = new ArrayList<>();
        for (Map.Entry<String, String> e : reactions.entrySet()) {
            int sep = e.getKey().indexOf('\0');
            out.add(new ReactionEntry(e.getKey().substring(0, sep),
                e.getKey().substring(sep + 1), e.getValue()));
        }
        out.sort(Comparator.comparing(ReactionEntry::chatId).thenComparing(ReactionEntry::messageId));
        return out;
    }

    /** @return snapshot of the LID → phone-number mappings */
    public Map<String, String> lidMappings() {
        return Map.copyOf(lidToPn);
    }

    /** @return all stored label associations, sorted deterministically */
    public List<LabelsAssociation> allLabelAssociations() {
        List<LabelsAssociation> out = new ArrayList<>(labelAssociations.values());
        out.sort(Comparator.comparing(LabelsAssociation::chatId)
            .thenComparing(LabelsAssociation::labelId)
            .thenComparing(a -> a.messageId() == null ? "" : a.messageId()));
        return out;
    }

    // -- Persistence restore hooks (package-private, for StoreJson only). --
    // Unlike the mutation methods above, these emit NO events: restoring a
    // saved store is not a live change, and replaying events for it would
    // duplicate every upsert/update a listener already saw. Callers must
    // restore chats before their messages (message restore does not touch
    // the chat's unread count or timestamp).

    void restoreChat(StoredChat chat) {
        if (chat == null || chat.id() == null) return;
        chats.put(chat.id(), new ChatState(chat.id(), chat.lastMessageTimestamp(),
            chat.unreadCount(), chat.pinned(), chat.archived()));
    }

    void restoreMessage(String chatId, MessageReceiver.Decoded decoded) {
        if (chatId == null || decoded == null) return;
        chats.computeIfAbsent(chatId, id -> new ChatState(id,
            decoded.timestamp(), 0, false, false));
        Object lock = chatLocks.computeIfAbsent(chatId, k -> new Object());
        synchronized (lock) {
            Deque<MessageReceiver.Decoded> deque =
                messages.computeIfAbsent(chatId, k -> new ArrayDeque<>());
            deque.addLast(decoded);
            while (deque.size() > maxMessagesPerChat) deque.removeFirst();
        }
    }

    void restoreContact(ContactsUpsert.Contact contact) {
        if (contact != null && contact.id() != null) contacts.put(contact.id(), contact);
    }

    void restoreGroup(GroupsUpsert.GroupInfo group) {
        if (group != null && group.id() != null) groups.put(group.id(), group);
    }

    void restoreLidMapping(String lid, String pn) {
        if (lid != null && pn != null) lidToPn.put(lid, pn);
    }

    void restoreReaction(String chatId, String messageId, String code) {
        if (chatId != null && messageId != null && code != null) {
            reactions.put(chatId + "\0" + messageId, code);
        }
    }

    void restorePresence(String chatId, String participant, PresenceInfo info) {
        if (chatId == null || participant == null || info == null) return;
        presences.computeIfAbsent(chatId, k -> new ConcurrentHashMap<>()).put(participant, info);
    }

    void restoreLabel(LabelsEdit label) {
        if (label != null && label.id() != null) labels.put(label.id(), label);
    }

    void restoreLabelAssociation(LabelsAssociation association) {
        if (association == null) return;
        String key = association.associationType() + "\0"
            + association.chatId() + "\0" + association.labelId() + "\0" + association.messageId();
        labelAssociations.put(key, association);
    }

    void restoreConnectionState(String state) {
        if (state != null) connectionState = state;
    }

    /**
     * Replaces this store's contents wholesale with another store's
     * (Increment 5, for {@code JaWaClient.loadStore}): chats, messages,
     * contacts, groups, LID mappings, reactions, presences, labels,
     * label associations and the connection state are copied, and
     * anything this store held that the other does not is dropped.
     * Like the restore hooks, this emits no events — loading a
     * snapshot is not a live change. Two things are deliberately
     * kept, not copied: this store's event-bus binding (the live
     * client's bus, not the snapshot's) and its
     * {@code maxMessagesPerChat} cap (a final construction property).
     * Message deques are copied, so later mutation of either store
     * does not affect the other.
     *
     * @param other store to copy from; must not be null
     */
    public void replaceWith(InMemoryStore other) {
        if (other == null) throw new IllegalArgumentException("other must not be null");
        chats.clear();
        chats.putAll(other.chats);
        messages.clear();
        for (Map.Entry<String, Deque<MessageReceiver.Decoded>> e : other.messages.entrySet()) {
            messages.put(e.getKey(), new ArrayDeque<>(e.getValue()));
        }
        chatLocks.clear();
        contacts.clear();
        contacts.putAll(other.contacts);
        groups.clear();
        groups.putAll(other.groups);
        lidToPn.clear();
        lidToPn.putAll(other.lidToPn);
        reactions.clear();
        reactions.putAll(other.reactions);
        presences.clear();
        for (Map.Entry<String, ConcurrentHashMap<String, PresenceInfo>> e : other.presences.entrySet()) {
            presences.put(e.getKey(), new ConcurrentHashMap<>(e.getValue()));
        }
        labels.clear();
        labels.putAll(other.labels);
        labelAssociations.clear();
        labelAssociations.putAll(other.labelAssociations);
        connectionState = other.connectionState;
    }
}
