package id.jawa.client;

import id.jawa.domain.model.ChatPresence;
import id.jawa.domain.model.ContactStatus;
import id.jawa.feature.appstate.AppPatchBuilder;
import id.jawa.feature.appstate.AppPatchEncoder;
import id.jawa.feature.appstate.AppPatchTransport;
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
     * Resolves a LID JID to a phone number JID, checking cache first and falling back to USync query.
     */
    public CompletableFuture<String> resolveLidToPn(String lidJid) {
        return client.resolveLidToPn(lidJid);
    }

    /**
     * Returns cached phone number JID for a LID if known.
     */
    public String getCachedPnForLid(String lidJid) {
        return JaWaClient.getCachedPnForLid(lidJid);
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

    // ---- Increment 3: contact / label SERVER patches (Baileys lib/Socket/chats.js + lib/Utils/chat-utils.js) ----
    //
    // The buildServer* methods build app-state patches only. They do NOT
    // send anything, and their semantics are unchanged by Increment 4:
    // they remain pure builders, named buildServer* to keep them
    // unmistakably separate from InMemoryStore's local label APIs
    // (upsertLabel / applyLabelAssociation), which this workstream does not touch.
    //
    // ---- Increment 4: sending (same Baileys sources, via AppPatchTransport) ----
    //
    // The sendServer* methods below build the same patch and actually
    // SEND it through a caller-supplied AppPatchTransport (resync,
    // encode, w:sync:app:state IQ, persist new version).
    //
    // ---- Increment 5: JaWaClient.appPatch hook ----
    //
    // The transport-taking overloads are unchanged: callers that
    // supply a transport (and a key id) keep exactly the Increment 4
    // behaviour. The new no-transport overloads below delegate to
    // {@code JaWaClient.appPatch}, which supplies the key id from
    // {@code creds.myAppStateKeyId}, the key lookup from the client's
    // app-state key storage, a resync adapter, and the client's version
    // store. A no-transport call on a client with no key id (or no
    // client at all) fails its future instead of sending — see
    // {@code JaWaClient.appPatch} for the failure and silent-failure
    // semantics, which are unchanged from the transport path.

    /**
     * Builds the app-state patch for adding or editing a contact
     * (Baileys {@code addOrEditContact}). Not sent — see the note above.
     *
     * @param jid contact JID
     * @param fullName full name, or null to leave unset
     * @param firstName first name, or null to leave unset
     * @param username username, or null to leave unset
     * @param lidJid LID JID, or null to leave unset
     * @param pnJid phone-number JID, or null to leave unset
     * @return the patch model for an appPatch transport
     */
    public AppPatchBuilder.AppPatch buildServerContactPatch(String jid, String fullName, String firstName,
                                                            String username, String lidJid, String pnJid) {
        return AppPatchBuilder.buildAddOrEditContact(jid, fullName, firstName, username, lidJid, pnJid);
    }

    /**
     * Builds the app-state patch for removing a contact (Baileys {@code removeContact}).
     * Not sent — see the note above.
     *
     * @param jid contact JID
     * @return the patch model for an appPatch transport
     */
    public AppPatchBuilder.AppPatch buildServerRemoveContactPatch(String jid) {
        return AppPatchBuilder.buildRemoveContact(jid);
    }

    /**
     * Builds the app-state patch for creating or editing a label
     * (Baileys {@code addLabel}). Wire {@code color} and
     * {@code predefinedId} are ints (see {@code wa.proto}); a null
     * {@code predefinedId} leaves the field unset. Not sent — see the note above.
     *
     * @param labelId label id
     * @param name label name, or null to leave unset
     * @param color label color code (wire int)
     * @param predefinedId predefined label id, or null
     * @param deleted true to mark the label deleted
     * @return the patch model for an appPatch transport
     */
    public AppPatchBuilder.AppPatch buildServerLabelEditPatch(String labelId, String name, int color,
                                                              Integer predefinedId, boolean deleted) {
        return AppPatchBuilder.buildLabelEdit(labelId, name, color, predefinedId, deleted);
    }

    /**
     * Builds the app-state patch for deleting a label (Baileys {@code addLabel}
     * with {@code deleted: true}). Not sent — see the note above.
     *
     * @param labelId label id
     * @return the patch model for an appPatch transport
     */
    public AppPatchBuilder.AppPatch buildServerLabelDeletePatch(String labelId) {
        return AppPatchBuilder.buildLabelDelete(labelId);
    }

    /**
     * Builds the app-state patch for labelling or unlabelling a chat
     * (Baileys {@code addChatLabel} / {@code removeChatLabel}).
     * Not sent — see the note above.
     *
     * @param chatJid chat JID
     * @param labelId label id
     * @param labeled true to add the label, false to remove it
     * @return the patch model for an appPatch transport
     */
    public AppPatchBuilder.AppPatch buildServerChatLabelPatch(String chatJid, String labelId, boolean labeled) {
        return AppPatchBuilder.buildChatLabel(chatJid, labelId, labeled);
    }

    /**
     * Builds the app-state patch for labelling or unlabelling a message
     * (Baileys {@code addMessageLabel} / {@code removeMessageLabel}).
     * Not sent — see the note above.
     *
     * @param chatJid chat JID
     * @param messageId message id
     * @param labelId label id
     * @param labeled true to add the label, false to remove it
     * @return the patch model for an appPatch transport
     */
    public AppPatchBuilder.AppPatch buildServerMessageLabelPatch(String chatJid, String messageId,
                                                                 String labelId, boolean labeled) {
        return AppPatchBuilder.buildMessageLabel(chatJid, messageId, labelId, labeled);
    }

    /**
     * Builds the app-state patch for a quick reply (Baileys
     * {@code addOrEditQuickReply} / {@code removeQuickReply} via
     * {@code chatModificationToAppPatch}). Not sent — see the note above.
     *
     * @param shortcut quick-reply shortcut, or null for ''
     * @param message quick-reply message text, or null for ''
     * @param timestampSeconds patch index timestamp (epoch seconds as a
     *                         string), or null to default to now
     * @param deleted true to mark the reply deleted (the remove shape)
     * @return the patch model for an appPatch transport
     */
    public AppPatchBuilder.AppPatch buildServerQuickReplyPatch(String shortcut, String message,
                                                               String timestampSeconds, boolean deleted) {
        return AppPatchBuilder.buildQuickReply(shortcut, message, timestampSeconds, deleted);
    }

    /**
     * Builds the app-state patch for removing a quick reply (Baileys
     * {@code removeQuickReply}: {@code deleted: true} at the reply's
     * timestamp index). Not sent — see the note above.
     *
     * @param timestampSeconds patch index timestamp of the reply to remove; required
     * @return the patch model for an appPatch transport
     */
    public AppPatchBuilder.AppPatch buildServerRemoveQuickReplyPatch(String timestampSeconds) {
        return AppPatchBuilder.buildRemoveQuickReply(timestampSeconds);
    }

    /**
     * Sends an already-built app-state patch through the given
     * transport (Baileys {@code appPatch}). The transport's resync,
     * encoding, IQ send and version persistence all apply; see
     * {@link AppPatchTransport} for the failure and version semantics.
     *
     * @param transport appPatch transport; a null transport fails the
     *                  future — use {@link #sendServerPatch(AppPatchBuilder.AppPatch)}
     *                  to send through the client's own appPatch hook instead
     * @param myKeyId raw id of the account's app-state-sync key
     * @param patch patch model, e.g. from a buildServer* method
     * @return future completing with the encoder result on a successful send
     */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerPatch(AppPatchTransport transport,
                                                                           byte[] myKeyId,
                                                                           AppPatchBuilder.AppPatch patch) {
        if (transport == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "no appPatch transport: supply an AppPatchTransport, or use the no-transport "
                    + "sendServerPatch(AppPatch) overload to send through JaWaClient.appPatch"));
        }
        return transport.send(patch, myKeyId);
    }

    /**
     * Increment 5: sends an already-built app-state patch through
     * {@code JaWaClient.appPatch} (key id, key lookup, resync and
     * version store all supplied by the client). Requires a
     * non-null client; a client with no {@code myAppStateKeyId} fails
     * the future (see {@code JaWaClient.appPatch}).
     */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerPatch(AppPatchBuilder.AppPatch patch) {
        if (client == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("no JaWaClient: cannot appPatch"));
        }
        return client.appPatch(patch);
    }

    /**
     * Builds and sends the add/edit-contact patch (Baileys
     * {@code addOrEditContact} via {@code appPatch}).
     */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerContactPatch(AppPatchTransport transport,
                                                                                  byte[] myKeyId, String jid,
                                                                                  String fullName, String firstName,
                                                                                  String username, String lidJid,
                                                                                  String pnJid) {
        return sendServerPatch(transport, myKeyId,
            buildServerContactPatch(jid, fullName, firstName, username, lidJid, pnJid));
    }

    /** Builds and sends the remove-contact patch (Baileys {@code removeContact} via {@code appPatch}). */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerRemoveContactPatch(AppPatchTransport transport,
                                                                                        byte[] myKeyId, String jid) {
        return sendServerPatch(transport, myKeyId, buildServerRemoveContactPatch(jid));
    }

    /** Builds and sends the create/edit-label patch (Baileys {@code addLabel} via {@code appPatch}). */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerLabelEditPatch(AppPatchTransport transport,
                                                                                    byte[] myKeyId, String labelId,
                                                                                    String name, int color,
                                                                                    Integer predefinedId, boolean deleted) {
        return sendServerPatch(transport, myKeyId,
            buildServerLabelEditPatch(labelId, name, color, predefinedId, deleted));
    }

    /** Builds and sends the delete-label patch (Baileys {@code addLabel} with deleted via {@code appPatch}). */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerLabelDeletePatch(AppPatchTransport transport,
                                                                                      byte[] myKeyId, String labelId) {
        return sendServerPatch(transport, myKeyId, buildServerLabelDeletePatch(labelId));
    }

    /** Builds and sends the chat-label patch (Baileys {@code addChatLabel}/{@code removeChatLabel}). */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerChatLabelPatch(AppPatchTransport transport,
                                                                                    byte[] myKeyId, String chatJid,
                                                                                    String labelId, boolean labeled) {
        return sendServerPatch(transport, myKeyId, buildServerChatLabelPatch(chatJid, labelId, labeled));
    }

    /** Builds and sends the message-label patch (Baileys {@code addMessageLabel}/{@code removeMessageLabel}). */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerMessageLabelPatch(AppPatchTransport transport,
                                                                                       byte[] myKeyId, String chatJid,
                                                                                       String messageId, String labelId,
                                                                                       boolean labeled) {
        return sendServerPatch(transport, myKeyId,
            buildServerMessageLabelPatch(chatJid, messageId, labelId, labeled));
    }

    /** Builds and sends the quick-reply patch (Baileys {@code addOrEditQuickReply} via {@code appPatch}). */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerQuickReplyPatch(AppPatchTransport transport,
                                                                                     byte[] myKeyId, String shortcut,
                                                                                     String message, String timestampSeconds,
                                                                                     boolean deleted) {
        return sendServerPatch(transport, myKeyId,
            buildServerQuickReplyPatch(shortcut, message, timestampSeconds, deleted));
    }

    /** Builds and sends the remove-quick-reply patch (Baileys {@code removeQuickReply} via {@code appPatch}). */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerRemoveQuickReplyPatch(AppPatchTransport transport,
                                                                                           byte[] myKeyId,
                                                                                           String timestampSeconds) {
        return sendServerPatch(transport, myKeyId,
            buildServerRemoveQuickReplyPatch(timestampSeconds));
    }

    // ---- Increment 5: no-transport overloads, delegating to JaWaClient.appPatch ----
    //
    // Same builds and sends as the transport-taking overloads above,
    // but the key id, key lookup, resync and version store come from
    // the client (creds.myAppStateKeyId and friends) instead of from
    // caller-supplied arguments. Signatures drop both the transport
    // and the key id; everything else is identical.

    /** Increment 5: builds and sends the add/edit-contact patch via {@code JaWaClient.appPatch}. */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerContactPatch(String jid,
                                                                                  String fullName, String firstName,
                                                                                  String username, String lidJid,
                                                                                  String pnJid) {
        return sendServerPatch(buildServerContactPatch(jid, fullName, firstName, username, lidJid, pnJid));
    }

    /** Increment 5: builds and sends the remove-contact patch via {@code JaWaClient.appPatch}. */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerRemoveContactPatch(String jid) {
        return sendServerPatch(buildServerRemoveContactPatch(jid));
    }

    /** Increment 5: builds and sends the create/edit-label patch via {@code JaWaClient.appPatch}. */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerLabelEditPatch(String labelId,
                                                                                    String name, int color,
                                                                                    Integer predefinedId, boolean deleted) {
        return sendServerPatch(buildServerLabelEditPatch(labelId, name, color, predefinedId, deleted));
    }

    /** Increment 5: builds and sends the delete-label patch via {@code JaWaClient.appPatch}. */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerLabelDeletePatch(String labelId) {
        return sendServerPatch(buildServerLabelDeletePatch(labelId));
    }

    /** Increment 5: builds and sends the chat-label patch via {@code JaWaClient.appPatch}. */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerChatLabelPatch(String chatJid,
                                                                                    String labelId, boolean labeled) {
        return sendServerPatch(buildServerChatLabelPatch(chatJid, labelId, labeled));
    }

    /** Increment 5: builds and sends the message-label patch via {@code JaWaClient.appPatch}. */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerMessageLabelPatch(String chatJid,
                                                                                       String messageId, String labelId,
                                                                                       boolean labeled) {
        return sendServerPatch(buildServerMessageLabelPatch(chatJid, messageId, labelId, labeled));
    }

    // ---- Increment 6: quick replies via JaWaClient.appPatch ----
    //
    // Baileys {@code addOrEditQuickReply} / {@code removeQuickReply}
    // (lib/Socket/chats.js:915/:923) are chatModify calls, so they go
    // through the same appPatch path as the label/contact patches
    // above. The addOrEdit/removeQuickReply names below are the
    // Baileys socket-method names; buildServer* remains the pure
    // builder, and sendServer* the transport-taking send, for callers
    // that already use that pattern.

    /** Builds and sends the add/edit-quick-reply patch via {@code JaWaClient.appPatch}. */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> addOrEditQuickReply(String shortcut,
                                                                               String message,
                                                                               String timestampSeconds) {
        return sendServerPatch(buildServerQuickReplyPatch(shortcut, message, timestampSeconds, false));
    }

    /** Builds and sends the add/edit-quick-reply patch through a caller-supplied transport. */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> addOrEditQuickReply(AppPatchTransport transport,
                                                                               byte[] myKeyId, String shortcut,
                                                                               String message, String timestampSeconds) {
        return sendServerPatch(transport, myKeyId,
            buildServerQuickReplyPatch(shortcut, message, timestampSeconds, false));
    }

    /** Builds and sends the remove-quick-reply patch via {@code JaWaClient.appPatch}. */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> removeQuickReply(String timestampSeconds) {
        return sendServerPatch(buildServerRemoveQuickReplyPatch(timestampSeconds));
    }

    /** Builds and sends the remove-quick-reply patch through a caller-supplied transport. */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> removeQuickReply(AppPatchTransport transport,
                                                                            byte[] myKeyId, String timestampSeconds) {
        return sendServerPatch(transport, myKeyId,
            buildServerRemoveQuickReplyPatch(timestampSeconds));
    }

    // ---- Increment 7: profile name + link-preview patches via JaWaClient.appPatch ----
    //
    // Baileys updateProfileName and updateDisableLinkPreviewsPrivacy
    // (lib/Socket/chats.js:285/:828) are chatModify calls, so like
    // the quick replies above they go through the same appPatch path
    // as the label/contact patches. The buildServer* methods build
    // only; sendServer* sends through a caller-supplied transport or
    // (no-transport overload) through JaWaClient.appPatch; the
    // Baileys-named methods delegate to the client, which owns the
    // key id and version store.
    //
    // Two push-name paths, stated not hidden: updateProfileName here
    // is the app-state path (server-side profile name, synced to
    // other devices). JaWaClient.updatePushName is the pre-existing
    // presence-based path (local credential + <presence name>
    // broadcast, no app-state patch). Neither replaces the other;
    // see JaWaClient.updateProfileName for the full relationship.

    /**
     * Builds the app-state patch for changing the profile name
     * (Baileys {@code updateProfileName} via {@code chatModify}).
     * Index {@code ['setting_pushName']}, type
     * {@code critical_block}, apiVersion 1, operation SET. The name
     * is passed through verbatim; only null is rejected. Not sent
     * — see the note above.
     *
     * @param name new profile (push) name; must not be null
     * @return the patch model for an appPatch transport
     */
    public AppPatchBuilder.AppPatch buildServerPushNamePatch(String name) {
        return AppPatchBuilder.buildPushNameSetting(name);
    }

    /** Builds and sends the profile-name patch through a caller-supplied transport. */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerPushNamePatch(AppPatchTransport transport,
                                                                                   byte[] myKeyId, String name) {
        return sendServerPatch(transport, myKeyId, buildServerPushNamePatch(name));
    }

    /** Increment 7: builds and sends the profile-name patch via {@code JaWaClient.appPatch}. */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerPushNamePatch(String name) {
        return sendServerPatch(buildServerPushNamePatch(name));
    }

    /**
     * Builds and sends the profile-name patch via
     * {@code JaWaClient.appPatch} (Baileys {@code updateProfileName}).
     * This is the app-state path, not the presence path — see the
     * note above and {@code JaWaClient.updateProfileName}.
     */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> updateProfileName(String name) {
        if (client == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("no JaWaClient: cannot appPatch"));
        }
        return client.updateProfileName(name);
    }

    /**
     * Builds the app-state patch for enabling/disabling link
     * previews (Baileys {@code updateDisableLinkPreviewsPrivacy} via
     * {@code chatModify}). Index
     * {@code ['setting_disableLinkPreviews']}, type
     * {@code regular}, apiVersion 8, operation SET. Not sent — see
     * the note above. Despite the name this is not a privacy IQ.
     *
     * @param isPreviewsDisabled true to disable link previews
     * @return the patch model for an appPatch transport
     */
    public AppPatchBuilder.AppPatch buildServerDisableLinkPreviewsPatch(boolean isPreviewsDisabled) {
        return AppPatchBuilder.buildDisableLinkPreviews(isPreviewsDisabled);
    }

    /** Builds and sends the link-preview patch through a caller-supplied transport. */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerDisableLinkPreviewsPatch(
            AppPatchTransport transport, byte[] myKeyId, boolean isPreviewsDisabled) {
        return sendServerPatch(transport, myKeyId, buildServerDisableLinkPreviewsPatch(isPreviewsDisabled));
    }

    /** Increment 7: builds and sends the link-preview patch via {@code JaWaClient.appPatch}. */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> sendServerDisableLinkPreviewsPatch(boolean isPreviewsDisabled) {
        return sendServerPatch(buildServerDisableLinkPreviewsPatch(isPreviewsDisabled));
    }

    /**
     * Builds and sends the link-preview patch via
     * {@code JaWaClient.appPatch} (Baileys
     * {@code updateDisableLinkPreviewsPrivacy}). Not a privacy IQ.
     */
    public CompletableFuture<AppPatchEncoder.EncodedPatch> updateDisableLinkPreviewsPrivacy(boolean isPreviewsDisabled) {
        if (client == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("no JaWaClient: cannot appPatch"));
        }
        return client.updateDisableLinkPreviewsPrivacy(isPreviewsDisabled);
    }

    /**
     * Increment 7: cleans dirty bits (Baileys {@code cleanDirtyBits},
     * {@code lib/Socket/chats.js:400}). Delegates to the client; see
     * {@code JaWaClient.cleanDirtyBits} for the sendNode-vs-IQ
     * divergence and the timestamp rule.
     *
     * @param type dirty-bit type, e.g. {@code account_sync}
     * @param fromTimestamp lower-bound timestamp, or null/0 to omit it
     * @return future completing when the server answers
     */
    public CompletableFuture<Void> cleanDirtyBits(String type, Long fromTimestamp) {
        if (client == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("no JaWaClient: cannot clean dirty bits"));
        }
        return client.cleanDirtyBits(type, fromTimestamp);
    }

}

