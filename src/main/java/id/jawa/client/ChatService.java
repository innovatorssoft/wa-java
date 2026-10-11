// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.client;

import id.jawa.domain.model.ChatPresence;
import id.jawa.domain.model.ContactStatus;
import id.jawa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service facade handling chat state and presence management in JaWa
 * (pin, archive, mute chats, presence updates, and checking numbers on WhatsApp).
 */
public final class ChatService {

    private final JaWaClient client;

    ChatService(JaWaClient client) {
        this.client = client;
    }

    /** Pin or unpin a chat. */
    public CompletableFuture<BinaryNode> pinChat(String chatJid, boolean pin) {
        return client.pinChat(chatJid, pin);
    }

    /** Archive or unarchive a chat. */
    public CompletableFuture<BinaryNode> archiveChat(String chatJid, boolean archive) {
        return client.archiveChat(chatJid, archive);
    }

    /** Mute a chat for the specified duration in seconds (0 = unmute). */
    public CompletableFuture<BinaryNode> muteChat(String chatJid, long durationSeconds) {
        return client.muteChat(chatJid, durationSeconds);
    }

    /** Send chat presence (composing / recording / paused) to a conversation. */
    public void sendChatPresence(String chatJid, ChatPresence state) {
        client.sendChatPresence(chatJid, state);
    }

    /** Send global presence (available / unavailable). */
    public void sendPresence(boolean available) {
        client.sendPresence(available);
    }

    /** Query if a list of phone numbers are registered on WhatsApp. */
    public CompletableFuture<Map<String, ContactStatus>> isOnWhatsApp(List<String> phoneNumbers) {
        return client.isOnWhatsApp(phoneNumbers);
    }
}
