package id.jawa.client;

import id.jawa.domain.model.NewsletterMessage;
import id.jawa.domain.model.NewsletterMetadata;
import id.jawa.feature.newsletter.NewsletterLinkedProfiles;
import id.jawa.feature.newsletter.NewsletterMessageDecoder;
import id.jawa.feature.newsletter.NewsletterMexQuery;
import id.jawa.feature.newsletter.NewsletterProfilePicture;
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

    // ---- Increment 3: newsletter administration via MEX (Baileys lib/Socket/newsletter.js) ----

    /**
     * Creates a newsletter channel (Baileys {@code newsletterCreate}) and
     * parses the create response into metadata. Sent through the existing
     * MEX transport; the query id and variables come from
     * {@link NewsletterMexQuery#create(String, String)}.
     *
     * @param name channel name
     * @param description channel description, or null
     * @return CompletableFuture resolving to the created channel metadata, or null when unparseable
     */
    public CompletableFuture<NewsletterMetadata> createNewsletter(String name, String description) {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.create(name, description);
        return client.sendMexQuery(req.queryId(), req.jsonVariables())
            .thenApply(NewsletterMexQuery::parseCreateResponse);
    }

    /**
     * Updates a newsletter channel (Baileys {@code newsletterUpdate}).
     * Only non-null fields are sent; {@code settings} is always null, as in Baileys.
     *
     * @param newsletterJid newsletter channel JID
     * @param name new name, or null to leave unchanged
     * @param description new description, or null to leave unchanged
     * @param pictureBase64 base64 picture data, or null to leave unchanged
     * @return CompletableFuture resolving to the raw MEX response JSON
     */
    public CompletableFuture<String> updateNewsletter(String newsletterJid, String name,
                                                      String description, String pictureBase64) {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.update(newsletterJid, name, description, pictureBase64);
        return client.sendMexQuery(req.queryId(), req.jsonVariables());
    }

    /**
     * Renames a newsletter channel (Baileys {@code newsletterUpdateName}).
     *
     * @param newsletterJid newsletter channel JID
     * @param name new name
     * @return CompletableFuture resolving to the raw MEX response JSON
     */
    public CompletableFuture<String> updateNewsletterName(String newsletterJid, String name) {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.updateName(newsletterJid, name);
        return client.sendMexQuery(req.queryId(), req.jsonVariables());
    }

    /**
     * Updates a newsletter channel description (Baileys {@code newsletterUpdateDescription}).
     *
     * @param newsletterJid newsletter channel JID
     * @param description new description
     * @return CompletableFuture resolving to the raw MEX response JSON
     */
    public CompletableFuture<String> updateNewsletterDescription(String newsletterJid, String description) {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.updateDescription(newsletterJid, description);
        return client.sendMexQuery(req.queryId(), req.jsonVariables());
    }

    /**
     * Updates a newsletter channel picture (Baileys
     * {@code newsletterUpdatePicture}, {@code lib/Socket/newsletter.js:105}).
     * The image is resized first with
     * {@link NewsletterProfilePicture#generate(byte[])} — a 720x720 JPEG
     * at quality 80, following Baileys' Jimp branch (top-left square
     * crop, bilinear resize; see that class for the sharp-branch
     * divergence) — then sent as base64 through the existing
     * {@code newsletterUpdate} MEX path ({@link #updateNewsletter}).
     *
     * @param newsletterJid newsletter channel JID
     * @param imageBytes source image bytes in any format {@code ImageIO} can read
     * @return CompletableFuture resolving to the raw MEX response JSON
     */
    public CompletableFuture<String> updateNewsletterPicture(String newsletterJid, byte[] imageBytes) {
        byte[] resized = NewsletterProfilePicture.generate(imageBytes);
        return updateNewsletterPictureBase64(newsletterJid,
            java.util.Base64.getEncoder().encodeToString(resized));
    }

    /**
     * Updates a newsletter channel picture from caller-supplied base64
     * (Baileys {@code newsletterUpdate} with a {@code picture} field).
     * Unlike {@link #updateNewsletterPicture(String, byte[])}, this
     * performs no resize: the caller owns the picture bytes and must
     * have resized them to Baileys' 720x720 JPEG spec already (use
     * {@link NewsletterProfilePicture} for that). This is the passthrough
     * for pictures that are already in final form.
     *
     * @param newsletterJid newsletter channel JID
     * @param pictureBase64 base64 picture data, already resized by the caller
     * @return CompletableFuture resolving to the raw MEX response JSON
     */
    public CompletableFuture<String> updateNewsletterPictureBase64(String newsletterJid, String pictureBase64) {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.update(newsletterJid, null, null, pictureBase64);
        return client.sendMexQuery(req.queryId(), req.jsonVariables());
    }

    /**
     * Removes a newsletter channel picture (Baileys {@code newsletterRemovePicture},
     * which sends an empty picture string).
     *
     * @param newsletterJid newsletter channel JID
     * @return CompletableFuture resolving to the raw MEX response JSON
     */
    public CompletableFuture<String> removeNewsletterPicture(String newsletterJid) {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.removePicture(newsletterJid);
        return client.sendMexQuery(req.queryId(), req.jsonVariables());
    }

    /**
     * Fetches newsletter subscribers (Baileys {@code newsletterSubscribers}).
     * Baileys returns the MEX result object verbatim, so this returns the raw JSON.
     *
     * @param newsletterJid newsletter channel JID
     * @return CompletableFuture resolving to the raw MEX response JSON
     */
    public CompletableFuture<String> getNewsletterSubscribers(String newsletterJid) {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.subscribers(newsletterJid);
        return client.sendMexQuery(req.queryId(), req.jsonVariables());
    }

    /**
     * Fetches all subscribed newsletters (Baileys {@code newsletterSubscribed}).
     * Baileys returns the MEX result object verbatim, so this returns the raw JSON.
     *
     * @return CompletableFuture resolving to the raw MEX response JSON
     */
    public CompletableFuture<String> getSubscribedNewsletters() {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.subscribed();
        return client.sendMexQuery(req.queryId(), req.jsonVariables());
    }

    /**
     * Fetches the admin count for a newsletter (Baileys {@code newsletterAdminCount}).
     *
     * @param newsletterJid newsletter channel JID
     * @return CompletableFuture resolving to the admin count, or null when absent/unparseable
     */
    public CompletableFuture<Integer> getNewsletterAdminCount(String newsletterJid) {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.adminCount(newsletterJid);
        return client.sendMexQuery(req.queryId(), req.jsonVariables())
            .thenApply(NewsletterMexQuery::parseAdminCount);
    }

    /**
     * Transfers newsletter ownership (Baileys {@code newsletterChangeOwner}).
     *
     * @param newsletterJid newsletter channel JID
     * @param newOwnerJid new owner user JID
     * @return CompletableFuture completing when the request succeeds
     */
    public CompletableFuture<Void> changeNewsletterOwner(String newsletterJid, String newOwnerJid) {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.changeOwner(newsletterJid, newOwnerJid);
        return client.sendMexQuery(req.queryId(), req.jsonVariables()).thenApply(resp -> null);
    }

    /**
     * Demotes a newsletter admin (Baileys {@code newsletterDemote}).
     * Note: Baileys defines no newsletter promote operation — promote only
     * appears as an inbound notification — so no promote facade exists here.
     *
     * @param newsletterJid newsletter channel JID
     * @param userJid admin user JID to demote
     * @return CompletableFuture completing when the request succeeds
     */
    public CompletableFuture<Void> demoteNewsletterAdmin(String newsletterJid, String userJid) {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.demote(newsletterJid, userJid);
        return client.sendMexQuery(req.queryId(), req.jsonVariables()).thenApply(resp -> null);
    }

    /**
     * Deletes a newsletter channel (Baileys {@code newsletterDelete}).
     *
     * @param newsletterJid newsletter channel JID
     * @return CompletableFuture completing when the request succeeds
     */
    public CompletableFuture<Void> deleteNewsletter(String newsletterJid) {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.delete(newsletterJid);
        return client.sendMexQuery(req.queryId(), req.jsonVariables()).thenApply(resp -> null);
    }

    /**
     * Fetches this account's new-chat message cap (Baileys
     * {@code fetchNewChatMessageCap} in {@code lib/Socket/socket.js}).
     * Baileys returns the MEX result object verbatim, so this returns the
     * raw JSON; no fields are invented from it. The matching inbound
     * notification payload is carried by {@code MessageCappingUpdate}.
     *
     * @return CompletableFuture resolving to the raw MEX response JSON
     */
    public CompletableFuture<String> fetchNewChatMessageCap() {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.messageCappingInfo();
        return client.sendMexQuery(req.queryId(), req.jsonVariables());
    }

    /**
     * Decodes newsletter fetch-response messages that carry a
     * {@code <plaintext>} child. Pure function — no I/O. See
     * {@link NewsletterMessageDecoder} for the covered path and its limits.
     *
     * @param messagesNode the {@code <messages>} node from a newsletter fetch response
     * @param newsletterJid newsletter channel JID the messages belong to
     * @return decoded messages, skipping any without decodable plaintext
     */
    public List<NewsletterMessage> decodeNewsletterMessages(BinaryNode messagesNode, String newsletterJid) {
        return NewsletterMessageDecoder.decodeAll(messagesNode, newsletterJid);
    }

    /**
     * Parses an inbound linked-profiles MEX notification. Pure function —
     * no I/O, and no dispatch is claimed: Baileys defines no outbound
     * linked-profiles query, so there is no send facade for it. Wiring this
     * parser to a MEX notification dispatch site is a parent hook.
     *
     * @param mexJson raw MEX notification JSON string
     * @return parsed update, or null when the payload does not match the linked-profiles shape
     */
    public NewsletterLinkedProfiles.LinkedProfilesUpdate parseLinkedProfilesNotification(String mexJson) {
        return NewsletterLinkedProfiles.parse(mexJson);
    }
}
