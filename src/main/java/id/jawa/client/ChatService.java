package id.jawa.client;

import id.jawa.domain.model.ChatPresence;
import id.jawa.domain.model.ContactStatus;
import id.jawa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class ChatService {

    private final JaWaClient client;

    ChatService(JaWaClient client) {
        this.client = client;
    }

    public CompletableFuture<BinaryNode> pinChat(String chatJid, boolean pin) {
        return client.pinChat(chatJid, pin);
    }

    public CompletableFuture<BinaryNode> archiveChat(String chatJid, boolean archive) {
        return client.archiveChat(chatJid, archive);
    }

    public CompletableFuture<BinaryNode> muteChat(String chatJid, long durationSeconds) {
        return client.muteChat(chatJid, durationSeconds);
    }

    public void sendChatPresence(String chatJid, ChatPresence state) {
        client.sendChatPresence(chatJid, state);
    }

    public void sendPresence(boolean available) {
        client.sendPresence(available);
    }

    public CompletableFuture<Map<String, ContactStatus>> isOnWhatsApp(List<String> phoneNumbers) {
        return client.isOnWhatsApp(phoneNumbers);
    }

    public CompletableFuture<Void> setDisappearingTimer(String chatJid, int timerSeconds) {
        return client.setDisappearingTimer(chatJid, timerSeconds);
    }
}
