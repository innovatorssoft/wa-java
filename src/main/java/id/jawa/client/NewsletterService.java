package id.jawa.client;

import id.jawa.proto.Wa;
import id.jawa.protocol.codec.BinaryNode;

import java.util.concurrent.CompletableFuture;

public final class NewsletterService {

    private final JaWaClient client;

    NewsletterService(JaWaClient client) {
        this.client = client;
    }

    public CompletableFuture<String> sendText(String newsletterJid, String text) {
        return client.sendNewsletterText(newsletterJid, text);
    }

    public CompletableFuture<String> sendMessage(String newsletterJid, Wa.Message msg) {
        return client.sendNewsletterMessage(newsletterJid, msg);
    }

    public CompletableFuture<String> sendReaction(String newsletterJid, String serverId, String reactionCode) {
        return client.sendNewsletterReaction(newsletterJid, serverId, reactionCode);
    }

    public CompletableFuture<BinaryNode> fetchMessages(String newsletterJid, int count) {
        return client.fetchNewsletterMessages(newsletterJid, count);
    }
}
