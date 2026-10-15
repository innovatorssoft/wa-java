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

    public CompletableFuture<Void> follow(String newsletterJid) {
        return client.followNewsletter(newsletterJid);
    }

    public CompletableFuture<Void> unfollow(String newsletterJid) {
        return client.unfollowNewsletter(newsletterJid);
    }

    public CompletableFuture<Void> toggleMute(String newsletterJid, boolean mute) {
        return client.toggleMuteNewsletter(newsletterJid, mute);
    }

    public CompletableFuture<id.jawa.domain.model.NewsletterMetadata> getInfo(String newsletterJid) {
        return client.getNewsletterInfo(newsletterJid);
    }

    public CompletableFuture<id.jawa.domain.model.NewsletterMetadata> getInfoWithInvite(String inviteCodeOrUrl) {
        return client.getNewsletterInfoWithInvite(inviteCodeOrUrl);
    }

    public CompletableFuture<String> sendMexQuery(String queryId, String jsonVariables) {
        return client.sendMexQuery(queryId, jsonVariables);
    }

    public CompletableFuture<Void> acceptTOSNotice(String noticeId, String stage) {
        return client.acceptTOSNotice(noticeId, stage);
    }

    public CompletableFuture<Void> acceptNewsletterTOSNotice() {
        return client.acceptTOSNotice("20601218", "5");
    }
}
