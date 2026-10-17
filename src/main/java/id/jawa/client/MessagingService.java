package id.jawa.client;

import id.jawa.feature.messaging.MessageEncoder;
import id.jawa.feature.messaging.MessageReceiver;
import id.jawa.proto.Wa;
import id.jawa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service managing core message transmission, replies, interactive buttons, carousels, receipts, and status stories.
 */
public final class MessagingService {

    private final JaWaClient client;

    MessagingService(JaWaClient client) {
        this.client = client;
    }

    /**
     * Sends a raw Protobuf message to a conversation.
     *
     * @param chatJid Target recipient conversation JID
     * @param msg Structured Wa.Message payload
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendMessage(String chatJid, Wa.Message msg) {
        return client.sendMessage(chatJid, msg);
    }

    /**
     * Sends a Protobuf message replying directly to a decoded incoming message.
     *
     * @param target Decoded incoming message
     * @param msg Structured Wa.Message payload
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendMessage(MessageReceiver.Decoded target, Wa.Message msg) {
        return client.sendMessage(target, msg);
    }

    /**
     * Sends a plain text message to a conversation.
     *
     * @param chatJid Target recipient conversation JID
     * @param text Text body
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendText(String chatJid, String text) {
        return client.sendText(chatJid, text);
    }

    /**
     * Sends a plain text message to the originating chat of an incoming message.
     *
     * @param target Decoded incoming message
     * @param text Text body
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendText(MessageReceiver.Decoded target, String text) {
        return client.sendText(target, text);
    }

    /**
     * Sends a text message with mentioned user tags.
     *
     * @param chatJid Target recipient conversation JID
     * @param text Text body containing '@number' mentions
     * @param mentionedJids List of participant JIDs tagged in the message
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendTextWithMentions(String chatJid, String text, List<String> mentionedJids) {
        return client.sendTextWithMentions(chatJid, text, mentionedJids);
    }

    /**
     * Sends a quoted reply referencing an earlier message.
     *
     * @param chatJid Target recipient conversation JID
     * @param text Reply text
     * @param quotedMsgId Message ID of the quoted message
     * @param quotedSenderJid Participant JID of the quoted message author
     * @param quotedText Preview text of the quoted message
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendReply(String chatJid, String text, String quotedMsgId, String quotedSenderJid, String quotedText) {
        return client.sendReply(chatJid, text, quotedMsgId, quotedSenderJid, quotedText);
    }

    /**
     * Sends a quoted reply without preview text.
     *
     * @param chatJid Target recipient conversation JID
     * @param text Reply text
     * @param quotedMsgId Message ID of the quoted message
     * @param quotedSenderJid Participant JID of the quoted message author
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendReply(String chatJid, String text, String quotedMsgId, String quotedSenderJid) {
        return client.sendReply(chatJid, text, quotedMsgId, quotedSenderJid, "");
    }

    /**
     * Sends a quoted reply replying to a decoded incoming message.
     *
     * @param target Decoded incoming message
     * @param text Reply text
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendReply(MessageReceiver.Decoded target, String text) {
        return client.sendReply(target, text);
    }

    /**
     * Sends an interactive poll with single or multiple choice options.
     *
     * @param chatJid Target recipient conversation JID
     * @param title Poll question
     * @param options List of answer choices
     * @param selectableCount Maximum number of answers a respondent can select (1 for single choice)
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendPoll(String chatJid, String title, List<String> options, int selectableCount) {
        return client.sendPoll(chatJid, title, options, selectableCount);
    }

    /**
     * Sends geographical coordinates as a map location pin.
     *
     * @param chatJid Target recipient conversation JID
     * @param lat Latitude degrees
     * @param lng Longitude degrees
     * @param name Place name or venue title
     * @param address Full street address
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendLocation(String chatJid, double lat, double lng, String name, String address) {
        return client.sendLocation(chatJid, lat, lng, name, address);
    }

    /**
     * Sends a contact vCard message.
     *
     * @param chatJid Target recipient conversation JID
     * @param displayName Contact card title
     * @param vcard Serialized vCard 3.0 string
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendContact(String chatJid, String displayName, String vcard) {
        return client.sendContact(chatJid, displayName, vcard);
    }

    /**
     * Sends an emoji reaction to an existing message.
     *
     * @param chatJid Target recipient conversation JID
     * @param targetMsgId Target message ID
     * @param targetParticipant Participant JID in group context
     * @param fromMe True if reacting to own message
     * @param emoji Reaction emoji string (e.g. "👍", "❤️"), or empty string to remove reaction
     * @return CompletableFuture resolving to reaction message ID
     */
    public CompletableFuture<String> sendReaction(String chatJid, String targetMsgId, String targetParticipant, boolean fromMe, String emoji) {
        return client.sendReaction(chatJid, targetMsgId, targetParticipant, fromMe, emoji);
    }

    /**
     * Sends an emoji reaction replying to a decoded incoming message.
     *
     * @param target Decoded incoming message
     * @param emoji Reaction emoji string
     * @return CompletableFuture resolving to reaction message ID
     */
    public CompletableFuture<String> sendReaction(MessageReceiver.Decoded target, String emoji) {
        return client.sendReaction(target, emoji);
    }

    /**
     * Sends an interactive single-select list menu message.
     *
     * @param chatJid Target recipient conversation JID
     * @param title List title
     * @param body Body description
     * @param footer Footer text
     * @param buttonText Prompt button label
     * @param sections Grouped sections and option rows
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendListMessage(String chatJid, String title, String body, String footer, String buttonText,
                                                     List<MessageEncoder.ListSection> sections) {
        return client.sendListMessage(chatJid, title, body, footer, buttonText, sections);
    }

    /**
     * Sends an interactive Call-To-Action (CTA) URL / copy / call button message.
     *
     * @param chatJid Target recipient conversation JID
     * @param body Message body
     * @param footer Footer text
     * @param buttons List of CTA action buttons
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendCtaButtons(String chatJid, String body, String footer,
                                                    List<MessageEncoder.CtaButton> buttons) {
        return client.sendCtaButtons(chatJid, body, footer, buttons);
    }

    /**
     * Sends interactive quick reply buttons.
     *
     * @param chatJid Target recipient conversation JID
     * @param body Message body
     * @param footer Footer text
     * @param buttons List of quick reply buttons
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendButtonsMessage(String chatJid, String body, String footer,
                                                       List<MessageEncoder.QuickReplyButton> buttons) {
        return client.sendButtonsMessage(chatJid, body, footer, buttons);
    }

    /**
     * Sends an interactive horizontal multi-card carousel message with quoted context.
     *
     * @param chatJid Target recipient conversation JID
     * @param body Message body
     * @param footer Footer text
     * @param cards List of carousel card inputs
     * @param quoted Quoted message context info
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendCarousel(String chatJid, String body, String footer,
                                                 List<JaWaClient.CarouselCardInput> cards, Wa.ContextInfo quoted) {
        return client.sendCarousel(chatJid, body, footer, cards, quoted);
    }

    /**
     * Sends an interactive horizontal multi-card carousel message.
     *
     * @param chatJid Target recipient conversation JID
     * @param body Message body
     * @param footer Footer text
     * @param cards List of carousel card inputs
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendCarousel(String chatJid, String body, String footer,
                                                 List<JaWaClient.CarouselCardInput> cards) {
        return client.sendCarousel(chatJid, body, footer, cards, null);
    }

    /**
     * Edits the text content of a previously sent message.
     *
     * @param chatJid Target recipient conversation JID
     * @param targetMsgId Target message ID to modify
     * @param newText Replacement text body
     * @return CompletableFuture resolving to protocol edit message ID
     */
    public CompletableFuture<String> sendEdit(String chatJid, String targetMsgId, String newText) {
        return client.sendEdit(chatJid, targetMsgId, newText);
    }

    /**
     * Edits the text content of an earlier message using its decoded representation.
     *
     * @param target Decoded target message
     * @param newText Replacement text body
     * @return CompletableFuture resolving to protocol edit message ID
     */
    public CompletableFuture<String> sendEdit(MessageReceiver.Decoded target, String newText) {
        return client.sendEdit(target, newText);
    }

    /**
     * Revokes (deletes for everyone) a previously sent message.
     *
     * @param chatJid Target recipient conversation JID
     * @param targetMsgId Message ID to revoke
     * @param targetParticipant Participant JID in group context
     * @param fromMe True if revoking own message
     * @return CompletableFuture resolving to protocol revoke message ID
     */
    public CompletableFuture<String> sendRevoke(String chatJid, String targetMsgId, String targetParticipant, boolean fromMe) {
        return client.sendRevoke(chatJid, targetMsgId, targetParticipant, fromMe);
    }

    /**
     * Revokes a message using its decoded representation.
     *
     * @param target Decoded target message
     * @return CompletableFuture resolving to protocol revoke message ID
     */
    public CompletableFuture<String> sendRevoke(MessageReceiver.Decoded target) {
        return client.sendRevoke(target);
    }

    /**
     * Posts a text status update (WhatsApp story).
     *
     * @param text Story text body
     * @param backgroundArgb 32-bit ARGB background color (e.g. 0xFF007ACC)
     * @param fontType Font style index (0-5)
     * @return CompletableFuture resolving to story message ID
     */
    public CompletableFuture<String> sendStatusText(String text, int backgroundArgb, int fontType) {
        return client.sendStatusText(text, backgroundArgb, fontType);
    }

    /**
     * Posts a media status update (image/video WhatsApp story).
     *
     * @param mediaMsg Prepared media Protobuf message payload
     * @return CompletableFuture resolving to story message ID
     */
    public CompletableFuture<String> sendStatusMedia(Wa.Message mediaMsg) {
        return client.sendStatusMedia(mediaMsg);
    }

    /**
     * Transmits a read receipt (blue double ticks) for a message.
     *
     * @param chatJid Conversation JID
     * @param msgId Message ID
     * @param senderJid Sender participant JID
     */
    public void sendReadReceipt(String chatJid, String msgId, String senderJid) {
        client.sendReadReceipt(chatJid, msgId, senderJid);
    }

    /**
     * Transmits a read receipt for a decoded incoming message.
     *
     * @param target Decoded message
     */
    public void sendReadReceipt(MessageReceiver.Decoded target) {
        client.sendReadReceipt(target);
    }

    /**
     * Transmits a played receipt for an audio note or voice message.
     *
     * @param chatJid Conversation JID
     * @param msgId Message ID
     * @param senderJid Sender participant JID
     */
    public void sendPlayedReceipt(String chatJid, String msgId, String senderJid) {
        client.sendPlayedReceipt(chatJid, msgId, senderJid);
    }

    /**
     * Transmits a played receipt for a decoded incoming message.
     *
     * @param target Decoded message
     */
    public void sendPlayedReceipt(MessageReceiver.Decoded target) {
        client.sendPlayedReceipt(target);
    }

    /**
     * Transmits a batch read receipt acknowledging multiple message IDs at once.
     *
     * @param chatJid Conversation JID
     * @param msgIds List of message IDs to acknowledge
     * @param senderJid Sender participant JID
     */
    public void sendReadReceiptBatch(String chatJid, List<String> msgIds, String senderJid) {
        client.sendReadReceiptBatch(chatJid, msgIds, senderJid);
    }

    /**
     * Stars or unstars a message in a conversation.
     *
     * @param chatJid Conversation JID
     * @param msgId Message ID
     * @param star True to star, false to unstar
     * @return CompletableFuture resolving to server BinaryNode response
     */
    public CompletableFuture<BinaryNode> starMessage(String chatJid, String msgId, boolean star) {
        return client.starMessage(chatJid, msgId, star);
    }

    /**
     * Stars or unstars a decoded message.
     *
     * @param target Decoded message
     * @param star True to star, false to unstar
     * @return CompletableFuture resolving to server BinaryNode response
     */
    public CompletableFuture<BinaryNode> starMessage(MessageReceiver.Decoded target, boolean star) {
        String chatJid = target.groupJid() != null ? target.groupJid() : target.senderJid();
        return client.starMessage(chatJid, target.msgId(), star);
    }

    /**
     * Sends a WebP sticker to a conversation.
     *
     * @param chatJid Target recipient conversation JID
     * @param webpBytes WebP file bytes
     * @param isAnimated True if sticker is animated
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendSticker(String chatJid, byte[] webpBytes, boolean isAnimated) {
        return client.sendSticker(chatJid, webpBytes, isAnimated);
    }

    /**
     * Sends a static WebP sticker to a conversation.
     *
     * @param chatJid Target recipient conversation JID
     * @param webpBytes WebP file bytes
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendSticker(String chatJid, byte[] webpBytes) {
        return client.sendSticker(chatJid, webpBytes, false);
    }

    /**
     * Transmits a self read receipt synchronizing read state to other companion devices.
     *
     * @param chatJid Conversation JID
     * @param msgId Message ID
     */
    public void sendReadSelfReceipt(String chatJid, String msgId) {
        client.sendReadSelfReceipt(chatJid, msgId);
    }

    /**
     * Transmits a self played receipt synchronizing playback state to other companion devices.
     *
     * @param chatJid Conversation JID
     * @param msgId Message ID
     */
    public void sendPlayedSelfReceipt(String chatJid, String msgId) {
        client.sendPlayedSelfReceipt(chatJid, msgId);
    }

    /**
     * Sends a media retry receipt to request re-upload of expired media.
     *
     * @param messageId Message ID
     * @param chatJid Conversation JID
     * @param senderJid Sender participant JID
     * @param isFromMe True if own message
     * @param isGroup True if group
     * @param mediaKey 32-byte media key
     * @return CompletableFuture completing when sent
     */
    public CompletableFuture<Void> sendMediaRetryReceipt(String messageId, String chatJid, String senderJid,
                                                         boolean isFromMe, boolean isGroup, byte[] mediaKey) {
        return client.sendMediaRetryReceipt(messageId, chatJid, senderJid, isFromMe, isGroup, mediaKey);
    }

    /**
     * Sends a media retry receipt for a decoded target message.
     *
     * @param target Decoded message
     * @param mediaKey 32-byte media key
     * @return CompletableFuture completing when sent
     */
    public CompletableFuture<Void> sendMediaRetryReceipt(MessageReceiver.Decoded target, byte[] mediaKey) {
        return client.sendMediaRetryReceipt(target, mediaKey);
    }

    /**
     * Generates a 22-character unique message ID prefixed with "3EB0".
     *
     * @return Standard WhatsApp Web message ID string
     */
    public String generateMessageId() {
        return client.generateMessageId();
    }
}
