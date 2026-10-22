package id.jawa.client;

import id.jawa.domain.model.NewsletterMessage;
import id.jawa.domain.model.NewsletterMetadata;
import id.jawa.proto.Wa;
import id.jawa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service managing WhatsApp channels (newsletters), subscriptions, updates, and MEX queries.
 */
public final class NewsletterService {

    private final JaWaClient client;

    NewsletterService(JaWaClient client) {
        this.client = client;
    }

    /**
     * Publishes a plain text broadcast to a newsletter channel.
     *
     * @param newsletterJid Newsletter channel JID (e.g. 12345@newsletter)
     * @param text Broadcast text content
     * @return CompletableFuture resolving to the sent message ID
     */
    public CompletableFuture<String> sendText(String newsletterJid, String text) {
        return client.sendNewsletterText(newsletterJid, text);
    }

    /**
     * Publishes a structured Protobuf message to a newsletter channel.
     *
     * @param newsletterJid Newsletter channel JID
     * @param msg Structured Wa.Message payload
     * @return CompletableFuture resolving to the sent message ID
     */
    public CompletableFuture<String> sendMessage(String newsletterJid, Wa.Message msg) {
        return client.sendNewsletterMessage(newsletterJid, msg);
    }

    /**
     * Sends an emoji reaction to a specific post in a newsletter channel.
     *
     * @param newsletterJid Newsletter channel JID
     * @param serverId Server message sequence ID of the post
     * @param reactionCode Emoji reaction string (e.g. "👍", "❤️")
     * @return CompletableFuture resolving to reaction message ID
     */
    public CompletableFuture<String> sendReaction(String newsletterJid, String serverId, String reactionCode) {
        return client.sendNewsletterReaction(newsletterJid, serverId, reactionCode);
    }

    /**
     * Fetches raw binary stanza containing recent posts from a newsletter.
     *
     * @param newsletterJid Newsletter channel JID
     * @param count Maximum number of messages to retrieve
     * @return CompletableFuture resolving to response BinaryNode
     */
    public CompletableFuture<BinaryNode> fetchMessages(String newsletterJid, int count) {
        return client.fetchNewsletterMessages(newsletterJid, count);
    }

    /**
     * Subscribes to real-time live stanza updates for a newsletter channel.
     *
     * @param newsletterJid Newsletter channel JID
     * @return CompletableFuture resolving to expiration timestamp
     */
    public CompletableFuture<Long> subscribeLiveUpdates(String newsletterJid) {
        return client.subscribeNewsletterLiveUpdates(newsletterJid);
    }

    /**
     * Sends view telemetry receipts for a batch of newsletter messages.
     *
     * @param newsletterJid Newsletter channel JID
     * @param serverIds List of post server sequence IDs viewed
     * @return CompletableFuture completing when receipts are sent
     */
    public CompletableFuture<Void> markViewed(String newsletterJid, List<Long> serverIds) {
        return client.markNewsletterViewed(newsletterJid, serverIds);
    }

    /**
     * Fetches historical messages from a newsletter with pagination.
     *
     * @param newsletterJid Newsletter channel JID
     * @param count Maximum number of messages to fetch
     * @param beforeServerId Server sequence ID boundary for older posts, or null
     * @return CompletableFuture resolving to list of NewsletterMessage
     */
    public CompletableFuture<List<NewsletterMessage>> getMessages(String newsletterJid, int count, Long beforeServerId) {
        return client.getNewsletterMessages(newsletterJid, count, beforeServerId);
    }

    /**
     * Fetches the most recent messages from a newsletter.
     *
     * @param newsletterJid Newsletter channel JID
     * @param count Maximum number of messages to fetch
     * @return CompletableFuture resolving to list of NewsletterMessage
     */
    public CompletableFuture<List<NewsletterMessage>> getMessages(String newsletterJid, int count) {
        return client.getNewsletterMessages(newsletterJid, count, null);
    }

    /**
     * Follows a WhatsApp newsletter channel.
     *
     * @param newsletterJid Newsletter channel JID
     * @return CompletableFuture completing when follow request succeeds
     */
    public CompletableFuture<Void> follow(String newsletterJid) {
        return client.followNewsletter(newsletterJid);
    }

    /**
     * Unfollows a WhatsApp newsletter channel.
     *
     * @param newsletterJid Newsletter channel JID
     * @return CompletableFuture completing when unfollow request succeeds
     */
    public CompletableFuture<Void> unfollow(String newsletterJid) {
        return client.unfollowNewsletter(newsletterJid);
    }

    /**
     * Mutes or unmutes notification alerts for a newsletter channel.
     *
     * @param newsletterJid Newsletter channel JID
     * @param mute True to mute, false to unmute
     * @return CompletableFuture completing when updated
     */
    public CompletableFuture<Void> toggleMute(String newsletterJid, boolean mute) {
        return client.toggleMuteNewsletter(newsletterJid, mute);
    }

    /**
     * Fetches metadata for a newsletter by its JID via MEX GraphQL.
     *
     * @param newsletterJid Newsletter channel JID
     * @return CompletableFuture resolving to NewsletterMetadata
     */
    public CompletableFuture<NewsletterMetadata> getInfo(String newsletterJid) {
        return client.getNewsletterInfo(newsletterJid);
    }

    /**
     * Fetches metadata for a newsletter using an invite code or wa.me/channel link.
     *
     * @param inviteCodeOrUrl Channel invite code or full URL
     * @return CompletableFuture resolving to NewsletterMetadata
     */
    public CompletableFuture<NewsletterMetadata> getInfoWithInvite(String inviteCodeOrUrl) {
        return client.getNewsletterInfoWithInvite(inviteCodeOrUrl);
    }

    /**
     * Executes a raw Meta MEX GraphQL query against WhatsApp servers.
     *
     * @param queryId Meta MEX operation query ID string
     * @param jsonVariables JSON variables string
     * @return CompletableFuture resolving to JSON result payload string
     */
    public CompletableFuture<String> sendMexQuery(String queryId, String jsonVariables) {
        return client.sendMexQuery(queryId, jsonVariables);
    }

    /**
     * Acknowledges acceptance of a Meta Terms of Service notice.
     *
     * @param noticeId Notice identifier
     * @param stage Notice funnel stage number
     * @return CompletableFuture completing when acknowledged
     */
    public CompletableFuture<Void> acceptTOSNotice(String noticeId, String stage) {
        return client.acceptTOSNotice(noticeId, stage);
    }

    /**
     * Acknowledges standard WhatsApp Newsletter Terms of Service notice.
     *
     * @return CompletableFuture completing when acknowledged
     */
    public CompletableFuture<Void> acceptNewsletterTOSNotice() {
        return client.acceptTOSNotice("20601218", "5");
    }
}
