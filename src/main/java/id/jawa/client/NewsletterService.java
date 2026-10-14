package id.jawa.client;

import id.jawa.domain.model.NewsletterMessage;
import id.jawa.proto.Wa;
import id.jawa.protocol.codec.BinaryNode;

import java.util.List;
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

    public CompletableFuture<Long> subscribeLiveUpdates(String newsletterJid) {
        return client.subscribeNewsletterLiveUpdates(newsletterJid);
    }

    public CompletableFuture<Void> markViewed(String newsletterJid, List<Long> serverIds) {
        return client.markNewsletterViewed(newsletterJid, serverIds);
    }

    public CompletableFuture<List<NewsletterMessage>> getMessages(String newsletterJid, int count, Long beforeServerId) {
        return client.getNewsletterMessages(newsletterJid, count, beforeServerId);
    }

    public CompletableFuture<List<NewsletterMessage>> getMessages(String newsletterJid, int count) {
        return client.getNewsletterMessages(newsletterJid, count, null);
    }
}
