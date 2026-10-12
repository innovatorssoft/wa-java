package id.jawa.client;

import id.jawa.feature.messaging.MessageEncoder;
import id.jawa.feature.messaging.MessageReceiver;
import id.jawa.proto.Wa;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class MessagingService {

    private final JaWaClient client;

    MessagingService(JaWaClient client) {
        this.client = client;
    }

    public CompletableFuture<String> sendMessage(String chatJid, Wa.Message msg) {
        return client.sendMessage(chatJid, msg);
    }

    public CompletableFuture<String> sendMessage(MessageReceiver.Decoded target, Wa.Message msg) {
        return client.sendMessage(target, msg);
    }

    public CompletableFuture<String> sendText(String chatJid, String text) {
        return client.sendText(chatJid, text);
    }

    public CompletableFuture<String> sendText(MessageReceiver.Decoded target, String text) {
        return client.sendText(target, text);
    }

    public CompletableFuture<String> sendTextWithMentions(String chatJid, String text, List<String> mentionedJids) {
        return client.sendTextWithMentions(chatJid, text, mentionedJids);
    }

    public CompletableFuture<String> sendReply(String chatJid, String text, String quotedMsgId, String quotedSenderJid, String quotedText) {
        return client.sendReply(chatJid, text, quotedMsgId, quotedSenderJid, quotedText);
    }

    public CompletableFuture<String> sendReply(String chatJid, String text, String quotedMsgId, String quotedSenderJid) {
        return client.sendReply(chatJid, text, quotedMsgId, quotedSenderJid, "");
    }

    public CompletableFuture<String> sendReply(MessageReceiver.Decoded target, String text) {
        return client.sendReply(target, text);
    }

    public CompletableFuture<String> sendPoll(String chatJid, String title, List<String> options, int selectableCount) {
        return client.sendPoll(chatJid, title, options, selectableCount);
    }

    public CompletableFuture<String> sendLocation(String chatJid, double lat, double lng, String name, String address) {
        return client.sendLocation(chatJid, lat, lng, name, address);
    }

    public CompletableFuture<String> sendContact(String chatJid, String displayName, String vcard) {
        return client.sendContact(chatJid, displayName, vcard);
    }

    public CompletableFuture<String> sendReaction(String chatJid, String targetMsgId, String targetParticipant, boolean fromMe, String emoji) {
        return client.sendReaction(chatJid, targetMsgId, targetParticipant, fromMe, emoji);
    }

    public CompletableFuture<String> sendReaction(MessageReceiver.Decoded target, String emoji) {
        return client.sendReaction(target, emoji);
    }

    public CompletableFuture<String> sendListMessage(String chatJid, String title, String body, String footer, String buttonText,
                                                     List<MessageEncoder.ListSection> sections) {
        return client.sendListMessage(chatJid, title, body, footer, buttonText, sections);
    }

    public CompletableFuture<String> sendCtaButtons(String chatJid, String body, String footer,
                                                    List<MessageEncoder.CtaButton> buttons) {
        return client.sendCtaButtons(chatJid, body, footer, buttons);
    }

    public CompletableFuture<String> sendButtonsMessage(String chatJid, String body, String footer,
                                                       List<MessageEncoder.QuickReplyButton> buttons) {
        return client.sendButtonsMessage(chatJid, body, footer, buttons);
    }

    public CompletableFuture<String> sendCarousel(String chatJid, String body, String footer,
                                                 List<JaWaClient.CarouselCardInput> cards, Wa.ContextInfo quoted) {
        return client.sendCarousel(chatJid, body, footer, cards, quoted);
    }

    public CompletableFuture<String> sendCarousel(String chatJid, String body, String footer,
                                                 List<JaWaClient.CarouselCardInput> cards) {
        return client.sendCarousel(chatJid, body, footer, cards, null);
    }

    public CompletableFuture<String> sendEdit(String chatJid, String targetMsgId, String newText) {
        return client.sendEdit(chatJid, targetMsgId, newText);
    }

    public CompletableFuture<String> sendEdit(MessageReceiver.Decoded target, String newText) {
        return client.sendEdit(target, newText);
    }

    public CompletableFuture<String> sendRevoke(String chatJid, String targetMsgId, String targetParticipant, boolean fromMe) {
        return client.sendRevoke(chatJid, targetMsgId, targetParticipant, fromMe);
    }

    public CompletableFuture<String> sendRevoke(MessageReceiver.Decoded target) {
        return client.sendRevoke(target);
    }

    public CompletableFuture<String> sendStatusText(String text, int backgroundArgb, int fontType) {
        return client.sendStatusText(text, backgroundArgb, fontType);
    }

    public CompletableFuture<String> sendStatusMedia(Wa.Message mediaMsg) {
        return client.sendStatusMedia(mediaMsg);
    }

    public void sendReadReceipt(String chatJid, String msgId, String senderJid) {
        client.sendReadReceipt(chatJid, msgId, senderJid);
    }

    public void sendReadReceipt(MessageReceiver.Decoded target) {
        client.sendReadReceipt(target);
    }

    public void sendPlayedReceipt(String chatJid, String msgId, String senderJid) {
        client.sendPlayedReceipt(chatJid, msgId, senderJid);
    }

    public void sendPlayedReceipt(MessageReceiver.Decoded target) {
        client.sendPlayedReceipt(target);
    }

    public void sendReadReceiptBatch(String chatJid, List<String> msgIds, String senderJid) {
        client.sendReadReceiptBatch(chatJid, msgIds, senderJid);
    }

    public String generateMessageId() {
        return client.generateMessageId();
    }
}
