package id.jawa.client;

import id.jawa.domain.model.ChatPresence;
import id.jawa.domain.model.ContactStatus;
import id.jawa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service managing individual conversations, chat presence, archiving, pinning, muting, and clearing.
 */
public final class ChatService {

    private final JaWaClient client;

    ChatService(JaWaClient client) {
        this.client = client;
    }

    /**
     * Pins or unpins a chat at the top of the conversation list.
     *
     * @param chatJid Conversation JID
     * @param pin True to pin, false to unpin
     * @return CompletableFuture resolving to response BinaryNode
     */
    public CompletableFuture<BinaryNode> pinChat(String chatJid, boolean pin) {
        return client.pinChat(chatJid, pin);
    }

    /**
     * Archives or unarchives a conversation.
     *
     * @param chatJid Conversation JID
     * @param archive True to archive, false to unarchive
     * @return CompletableFuture resolving to response BinaryNode
     */
    public CompletableFuture<BinaryNode> archiveChat(String chatJid, boolean archive) {
        return client.archiveChat(chatJid, archive);
    }

    /**
     * Mutes notifications for a chat for a specified duration.
     *
     * @param chatJid Conversation JID
     * @param durationSeconds Duration in seconds (e.g. 28800 for 8h, 604800 for 1 week, -1 for forever, 0 to unmute)
     * @return CompletableFuture resolving to response BinaryNode
     */
    public CompletableFuture<BinaryNode> muteChat(String chatJid, long durationSeconds) {
        return client.muteChat(chatJid, durationSeconds);
    }

    /**
     * Stars or unstars a specific message within a conversation.
     *
     * @param chatJid Conversation JID
     * @param msgId Message unique identifier
     * @param star True to star, false to unstar
     * @return CompletableFuture resolving to response BinaryNode
     */
    public CompletableFuture<BinaryNode> starMessage(String chatJid, String msgId, boolean star) {
        return client.starMessage(chatJid, msgId, star);
    }

    /**
     * Deletes an entire conversation thread.
     *
     * @param chatJid Conversation JID
     * @return CompletableFuture resolving to response BinaryNode
     */
    public CompletableFuture<BinaryNode> deleteChat(String chatJid) {
        return client.deleteChat(chatJid);
    }

    /**
     * Clears all message history from a chat without removing the conversation thread.
     *
     * @param chatJid Conversation JID
     * @return CompletableFuture resolving to response BinaryNode
     */
    public CompletableFuture<BinaryNode> clearChat(String chatJid) {
        return client.clearChat(chatJid);
    }

    /**
     * Sends ephemeral chat typing or audio recording indicator to a conversation.
     *
     * @param chatJid Conversation JID
     * @param state Presence state (COMPOSING, RECORDING, PAUSED)
     */
    public void sendChatPresence(String chatJid, ChatPresence state) {
        client.sendChatPresence(chatJid, state);
    }

    /**
     * Broadcasts global user presence (available / online or unavailable / offline).
     *
     * @param available True for available, false for unavailable
     */
    public void sendPresence(boolean available) {
        client.sendPresence(available);
    }

    /**
     * Checks if a list of phone numbers are registered on WhatsApp.
     *
     * @param phoneNumbers List of international phone numbers without formatting (e.g. "628123456789")
     * @return CompletableFuture resolving to Map of phone number to ContactStatus
     */
    public CompletableFuture<Map<String, ContactStatus>> isOnWhatsApp(List<String> phoneNumbers) {
        return client.isOnWhatsApp(phoneNumbers);
    }

    /**
     * Configures the disappearing messages expiration timer for a specific conversation.
     *
     * @param chatJid Conversation JID
     * @param timerSeconds Expiration period in seconds (e.g. 86400, 604800, 7776000, 0 to disable)
     * @return CompletableFuture completing when updated
     */
    public CompletableFuture<Void> setDisappearingTimer(String chatJid, int timerSeconds) {
        return client.setDisappearingTimer(chatJid, timerSeconds);
    }

    /**
     * Returns the cached disappearing message timer in seconds for a specific conversation.
     *
     * @param chatJid Conversation JID
     * @return Expiration seconds, or 0 if disabled
     */
    public int getDisappearingTimer(String chatJid) {
        return client.getChatEphemeralTimer(chatJid);
    }

    /**
     * Subscribes to presence updates from a specific contact.
     *
     * @param jid Target contact JID
     */
    public void subscribePresence(String jid) {
        client.subscribePresence(jid);
    }
}
