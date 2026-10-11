// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.client;

import id.jawa.feature.messaging.MessageEncoder;
import id.jawa.feature.messaging.MessageReceiver;
import id.jawa.proto.Wa;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service facade handling messaging operations in JaWa (text, mentions, replies,
 * interactive CTA/list/carousel, polls, locations, contacts, reactions, and receipts).
 */
public final class MessagingService {

    private final JaWaClient client;

    MessagingService(JaWaClient client) {
        this.client = client;
    }

    /** Send a built Protobuf message to a chat JID. */
    public CompletableFuture<String> sendMessage(String chatJid, Wa.Message msg) {
        return client.sendMessage(chatJid, msg);
    }

    /** Send a built Protobuf message to the chat of an inbound message. */
    public CompletableFuture<String> sendMessage(MessageReceiver.Decoded target, Wa.Message msg) {
        return client.sendMessage(target, msg);
    }

    /** Send a plain text message to a user or group. */
    public CompletableFuture<String> sendText(String chatJid, String text) {
        return client.sendText(chatJid, text);
    }

    /** Send a plain text message to the chat of an inbound message. */
    public CompletableFuture<String> sendText(MessageReceiver.Decoded target, String text) {
        return client.sendText(target, text);
    }

    /** Send a text message mentioning specific JIDs. */
    public CompletableFuture<String> sendTextWithMentions(String chatJid, String text, List<String> mentionedJids) {
        return client.sendTextWithMentions(chatJid, text, mentionedJids);
    }

    /** Send a reply quoting a specific message ID, sender JID, and preview text. */
    public CompletableFuture<String> sendReply(String chatJid, String text, String quotedMsgId, String quotedSenderJid, String quotedText) {
        return client.sendReply(chatJid, text, quotedMsgId, quotedSenderJid, quotedText);
    }

    /** Send a reply quoting a specific message ID and sender JID. */
    public CompletableFuture<String> sendReply(String chatJid, String text, String quotedMsgId, String quotedSenderJid) {
        return client.sendReply(chatJid, text, quotedMsgId, quotedSenderJid, "");
    }

    /** Send a reply quoting an inbound decoded message. */
    public CompletableFuture<String> sendReply(MessageReceiver.Decoded target, String text) {
        return client.sendReply(target, text);
    }

    /** Send a voting poll message. */
    public CompletableFuture<String> sendPoll(String chatJid, String title, List<String> options, int selectableCount) {
        return client.sendPoll(chatJid, title, options, selectableCount);
    }

    /** Send a geo-location pin message. */
    public CompletableFuture<String> sendLocation(String chatJid, double lat, double lng, String name, String address) {
        return client.sendLocation(chatJid, lat, lng, name, address);
    }

    /** Send a vCard contact card message. */
    public CompletableFuture<String> sendContact(String chatJid, String displayName, String vcard) {
        return client.sendContact(chatJid, displayName, vcard);
    }

    /** Send an emoji reaction to a target message. */
    public CompletableFuture<String> sendReaction(String chatJid, String targetMsgId, String targetParticipant, boolean fromMe, String emoji) {
        return client.sendReaction(chatJid, targetMsgId, targetParticipant, fromMe, emoji);
    }

    /** Send an emoji reaction using an inbound decoded message as reference. */
    public CompletableFuture<String> sendReaction(MessageReceiver.Decoded target, String emoji) {
        return client.sendReaction(target, emoji);
    }

    /** Send an interactive single-select dropdown list message. */
    public CompletableFuture<String> sendListMessage(String chatJid, String title, String body, String footer, String buttonText,
                                                     List<MessageEncoder.ListSection> sections) {
        return client.sendListMessage(chatJid, title, body, footer, buttonText, sections);
    }

    /** Send interactive call-to-action (CTA) buttons (URL, Copy Code, Quick Reply). */
    public CompletableFuture<String> sendCtaButtons(String chatJid, String body, String footer,
                                                    List<MessageEncoder.CtaButton> buttons) {
        return client.sendCtaButtons(chatJid, body, footer, buttons);
    }

    /** Send quick reply button message. */
    public CompletableFuture<String> sendButtonsMessage(String chatJid, String body, String footer,
                                                       List<MessageEncoder.QuickReplyButton> buttons) {
        return client.sendButtonsMessage(chatJid, body, footer, buttons);
    }

    /** Send horizontal carousel cards slider with quoted context info. */
    public CompletableFuture<String> sendCarousel(String chatJid, String body, String footer,
                                                 List<JaWaClient.CarouselCardInput> cards, Wa.ContextInfo quoted) {
        return client.sendCarousel(chatJid, body, footer, cards, quoted);
    }

    /** Send horizontal carousel cards slider without quoted message. */
    public CompletableFuture<String> sendCarousel(String chatJid, String body, String footer,
                                                 List<JaWaClient.CarouselCardInput> cards) {
        return client.sendCarousel(chatJid, body, footer, cards, null);
    }

    /** Edit a previously sent message. */
    public CompletableFuture<String> sendEdit(String chatJid, String targetMsgId, String newText) {
        return client.sendEdit(chatJid, targetMsgId, newText);
    }

    /** Edit a previously sent message referencing a decoded message. */
    public CompletableFuture<String> sendEdit(MessageReceiver.Decoded target, String newText) {
        return client.sendEdit(target, newText);
    }

    /** Revoke (delete for everyone) a previously sent message. */
    public CompletableFuture<String> sendRevoke(String chatJid, String targetMsgId, String targetParticipant, boolean fromMe) {
        return client.sendRevoke(chatJid, targetMsgId, targetParticipant, fromMe);
    }

    /** Revoke (delete for everyone) a previously sent message referencing a decoded message. */
    public CompletableFuture<String> sendRevoke(MessageReceiver.Decoded target) {
        return client.sendRevoke(target);
    }

    /** Post a text status/story. */
    public CompletableFuture<String> sendStatusText(String text, int backgroundArgb, int fontType) {
        return client.sendStatusText(text, backgroundArgb, fontType);
    }

    /** Post a media status/story. */
    public CompletableFuture<String> sendStatusMedia(Wa.Message mediaMsg) {
        return client.sendStatusMedia(mediaMsg);
    }

    /** Send a read receipt (blue ticks) for an inbound message. */
    public void sendReadReceipt(String chatJid, String msgId, String senderJid) {
        client.sendReadReceipt(chatJid, msgId, senderJid);
    }

    /** Send a read receipt (blue ticks) using a decoded message reference. */
    public void sendReadReceipt(MessageReceiver.Decoded target) {
        client.sendReadReceipt(target);
    }

    /** Send a played receipt for audio/voice note. */
    public void sendPlayedReceipt(String chatJid, String msgId, String senderJid) {
        client.sendPlayedReceipt(chatJid, msgId, senderJid);
    }

    /** Send a played receipt using a decoded message reference. */
    public void sendPlayedReceipt(MessageReceiver.Decoded target) {
        client.sendPlayedReceipt(target);
    }

    /** Send a batch read receipt. */
    public void sendReadReceiptBatch(String chatJid, List<String> msgIds, String senderJid) {
        client.sendReadReceiptBatch(chatJid, msgIds, senderJid);
    }
}
