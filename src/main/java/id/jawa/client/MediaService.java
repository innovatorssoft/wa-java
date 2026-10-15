package id.jawa.client;

import id.jawa.feature.media.MediaConn;
import id.jawa.feature.media.MediaCrypto;
import id.jawa.feature.messaging.MessageReceiver;
import id.jawa.proto.Wa;

import java.util.concurrent.CompletableFuture;

public final class MediaService {

    private final JaWaClient client;

    MediaService(JaWaClient client) {
        this.client = client;
    }

    public byte[] downloadMedia(Wa.Message msg) {
        return client.downloadMedia(msg);
    }

    public byte[] downloadMedia(MessageReceiver.Decoded target) {
        return client.downloadMedia(target);
    }

    public CompletableFuture<byte[]> downloadMedia(String url, String directPath, byte[] mediaKey,
                                                   byte[] fileEncSha256, MediaCrypto.MediaType type) {
        return client.downloadMedia(url, directPath, mediaKey, fileEncSha256, type);
    }

    public CompletableFuture<String> sendImage(String chatJid, byte[] imageBytes, String mimetype, String caption) {
        return client.sendImage(chatJid, imageBytes, mimetype, caption);
    }

    public CompletableFuture<String> sendImage(String chatJid, byte[] imageBytes, String caption) {
        return client.sendImage(chatJid, imageBytes, "image/jpeg", caption);
    }

    public CompletableFuture<String> sendImageViewOnce(String chatJid, byte[] imageBytes, String mimetype, String caption) {
        return client.sendImageViewOnce(chatJid, imageBytes, mimetype, caption);
    }

    public CompletableFuture<String> sendVideo(String chatJid, byte[] videoBytes, String mimetype, String caption,
                                              int seconds, int width, int height) {
        return client.sendVideo(chatJid, videoBytes, mimetype, caption, seconds, width, height);
    }

    public CompletableFuture<String> sendVideo(String chatJid, byte[] videoBytes, String mimetype, String caption) {
        return client.sendVideo(chatJid, videoBytes, mimetype, caption, 0, 0, 0);
    }

    public CompletableFuture<String> sendVideoViewOnce(String chatJid, byte[] videoBytes, String mimetype, String caption,
                                                      int seconds, int width, int height) {
        return client.sendVideoViewOnce(chatJid, videoBytes, mimetype, caption, seconds, width, height);
    }

    public CompletableFuture<String> sendAudio(String chatJid, byte[] audioBytes, String mimetype, int seconds, boolean ptt) {
        return client.sendAudio(chatJid, audioBytes, mimetype, seconds, ptt);
    }

    public CompletableFuture<String> sendAudioViewOnce(String chatJid, byte[] audioBytes, String mimetype, int seconds, boolean ptt) {
        return client.sendAudioViewOnce(chatJid, audioBytes, mimetype, seconds, ptt);
    }

    public CompletableFuture<String> sendDocument(String chatJid, byte[] docBytes, String mimetype, String fileName, String title) {
        return client.sendDocument(chatJid, docBytes, mimetype, fileName, title);
    }

    public CompletableFuture<String> sendSticker(String chatJid, byte[] webpBytes, boolean isAnimated) {
        return client.sendSticker(chatJid, webpBytes, isAnimated);
    }

    public CompletableFuture<String> sendSticker(String chatJid, byte[] webpBytes) {
        return client.sendSticker(chatJid, webpBytes, false);
    }

    public CompletableFuture<Void> deleteMedia(String directPath, byte[] encFileHash, MediaCrypto.MediaType type) {
        return client.deleteMedia(directPath, encFileHash, type);
    }

    public CompletableFuture<Void> sendMediaRetryReceipt(String messageId, String chatJid, String senderJid,
                                                         boolean isFromMe, boolean isGroup, byte[] mediaKey) {
        return client.sendMediaRetryReceipt(messageId, chatJid, senderJid, isFromMe, isGroup, mediaKey);
    }

    public CompletableFuture<Void> sendMediaRetryReceipt(MessageReceiver.Decoded target, byte[] mediaKey) {
        return client.sendMediaRetryReceipt(target, mediaKey);
    }

    public CompletableFuture<MediaConn> refreshMediaConn() {
        return client.refreshMediaConn();
    }
}
