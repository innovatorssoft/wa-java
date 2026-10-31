package id.ja_wa.client;

import id.ja_wa.feature.media.MediaConn;
import id.ja_wa.feature.media.MediaCrypto;
import id.ja_wa.feature.messaging.MessageReceiver;
import id.ja_wa.proto.Wa;

import java.util.concurrent.CompletableFuture;

/**
 * Service managing media upload, encrypted download, stickers, voice notes, and media retry tokens.
 */
public final class MediaService {

    private final JaWaClient client;

    MediaService(JaWaClient client) {
        this.client = client;
    }

    /**
     * Downloads and decrypts the media attachment within a Protobuf message synchronously.
     *
     * @param msg Protobuf message containing image, video, audio, document, or sticker
     * @return Decrypted raw file bytes, or null if no media attachment
     */
    public byte[] downloadMedia(Wa.Message msg) {
        return client.downloadMedia(msg);
    }

    /**
     * Downloads and decrypts the media payload from an incoming decoded message.
     *
     * @param target Decoded incoming message
     * @return Decrypted file bytes, or null
     */
    public byte[] downloadMedia(MessageReceiver.Decoded target) {
        return client.downloadMedia(target);
    }

    /**
     * Downloads and decrypts media asynchronously using direct CDN parameters.
     *
     * @param url Direct download URL
     * @param directPath Internal CDN direct path
     * @param mediaKey 32-byte cryptographic media key
     * @param fileEncSha256 SHA-256 hash of the encrypted ciphertext
     * @param type Media type classification
     * @return CompletableFuture resolving to decrypted file bytes
     */
    public CompletableFuture<byte[]> downloadMedia(String url, String directPath, byte[] mediaKey,
                                                   byte[] fileEncSha256, MediaCrypto.MediaType type) {
        return client.downloadMedia(url, directPath, mediaKey, fileEncSha256, type);
    }

    /**
     * Uploads and sends an encrypted image.
     *
     * @param chatJid Target recipient conversation JID
     * @param imageBytes Raw image file bytes
     * @param mimetype MIME type string (e.g. "image/jpeg", "image/png")
     * @param caption Optional text caption
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendImage(String chatJid, byte[] imageBytes, String mimetype, String caption) {
        return client.sendImage(chatJid, imageBytes, mimetype, caption);
    }

    /**
     * Uploads and sends a JPEG image with default MIME type "image/jpeg".
     *
     * @param chatJid Target recipient conversation JID
     * @param imageBytes JPEG file bytes
     * @param caption Optional text caption
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendImage(String chatJid, byte[] imageBytes, String caption) {
        return client.sendImage(chatJid, imageBytes, "image/jpeg", caption);
    }

    /**
     * Uploads and sends a view-once expiring image.
     *
     * @param chatJid Target recipient conversation JID
     * @param imageBytes Raw image file bytes
     * @param mimetype Image MIME type
     * @param caption Optional text caption
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendImageViewOnce(String chatJid, byte[] imageBytes, String mimetype, String caption) {
        return client.sendImageViewOnce(chatJid, imageBytes, mimetype, caption);
    }

    /**
     * Uploads and sends an encrypted video message.
     *
     * @param chatJid Target recipient conversation JID
     * @param videoBytes MP4 video bytes
     * @param mimetype Video MIME type (e.g. "video/mp4")
     * @param caption Optional caption
     * @param seconds Video duration in seconds
     * @param width Frame width in pixels
     * @param height Frame height in pixels
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendVideo(String chatJid, byte[] videoBytes, String mimetype, String caption,
                                              int seconds, int width, int height) {
        return client.sendVideo(chatJid, videoBytes, mimetype, caption, seconds, width, height);
    }

    /**
     * Uploads and sends an encrypted video message with default dimensions.
     *
     * @param chatJid Target recipient conversation JID
     * @param videoBytes MP4 video bytes
     * @param mimetype Video MIME type
     * @param caption Optional caption
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendVideo(String chatJid, byte[] videoBytes, String mimetype, String caption) {
        return client.sendVideo(chatJid, videoBytes, mimetype, caption, 0, 0, 0);
    }

    /**
     * Uploads and sends a view-once expiring video message.
     *
     * @param chatJid Target recipient conversation JID
     * @param videoBytes MP4 video bytes
     * @param mimetype Video MIME type
     * @param caption Optional caption
     * @param seconds Video duration in seconds
     * @param width Frame width
     * @param height Frame height
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendVideoViewOnce(String chatJid, byte[] videoBytes, String mimetype, String caption,
                                                      int seconds, int width, int height) {
        return client.sendVideoViewOnce(chatJid, videoBytes, mimetype, caption, seconds, width, height);
    }

    /**
     * Uploads and sends an audio message or push-to-talk voice note.
     *
     * @param chatJid Target recipient conversation JID
     * @param audioBytes OGG/Opus or MP3 audio bytes
     * @param mimetype Audio MIME type (e.g. "audio/ogg; codecs=opus")
     * @param seconds Playback duration in seconds
     * @param ptt True if this is a push-to-talk voice note, false for standard audio file
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendAudio(String chatJid, byte[] audioBytes, String mimetype, int seconds, boolean ptt) {
        return client.sendAudio(chatJid, audioBytes, mimetype, seconds, ptt);
    }

    /**
     * Uploads and sends a view-once audio voice note.
     *
     * @param chatJid Target recipient conversation JID
     * @param audioBytes Audio bytes
     * @param mimetype Audio MIME type
     * @param seconds Duration in seconds
     * @param ptt True for voice note
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendAudioViewOnce(String chatJid, byte[] audioBytes, String mimetype, int seconds, boolean ptt) {
        return client.sendAudioViewOnce(chatJid, audioBytes, mimetype, seconds, ptt);
    }

    /**
     * Uploads and sends an encrypted document attachment.
     *
     * @param chatJid Target recipient conversation JID
     * @param docBytes Raw document bytes
     * @param mimetype Document MIME type (e.g. "application/pdf")
     * @param fileName Display file name (e.g. "document.pdf")
     * @param title Document title or caption
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendDocument(String chatJid, byte[] docBytes, String mimetype, String fileName, String title) {
        return client.sendDocument(chatJid, docBytes, mimetype, fileName, title);
    }

    /**
     * Uploads and sends a WebP sticker.
     *
     * @param chatJid Target recipient conversation JID
     * @param webpBytes WebP format image bytes
     * @param isAnimated True if sticker is animated, false for static
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendSticker(String chatJid, byte[] webpBytes, boolean isAnimated) {
        return client.sendSticker(chatJid, webpBytes, isAnimated);
    }

    /**
     * Uploads and sends a static WebP sticker.
     *
     * @param chatJid Target recipient conversation JID
     * @param webpBytes WebP format image bytes
     * @return CompletableFuture resolving to sent message ID
     */
    public CompletableFuture<String> sendSticker(String chatJid, byte[] webpBytes) {
        return client.sendSticker(chatJid, webpBytes, false);
    }

    /**
     * Deletes an uploaded media file from the WhatsApp CDN storage servers.
     *
     * @param directPath Storage direct path
     * @param encFileHash SHA-256 hash of the encrypted file
     * @param type Media type
     * @return CompletableFuture completing when deleted
     */
    public CompletableFuture<Void> deleteMedia(String directPath, byte[] encFileHash, MediaCrypto.MediaType type) {
        return client.deleteMedia(directPath, encFileHash, type);
    }

    /**
     * Sends a media re-upload request receipt when an incoming media download fails with 404 or expired token.
     *
     * @param messageId Message ID of the missing media
     * @param chatJid Conversation JID
     * @param senderJid Sender participant JID
     * @param isFromMe Whether the message was originally sent by this account
     * @param isGroup Whether conversation is a group
     * @param mediaKey 32-byte media key
     * @return CompletableFuture completing when retry receipt is transmitted
     */
    public CompletableFuture<Void> sendMediaRetryReceipt(String messageId, String chatJid, String senderJid,
                                                         boolean isFromMe, boolean isGroup, byte[] mediaKey) {
        return client.sendMediaRetryReceipt(messageId, chatJid, senderJid, isFromMe, isGroup, mediaKey);
    }

    /**
     * Sends a media retry receipt for a decoded incoming message target.
     *
     * @param target Decoded message
     * @param mediaKey 32-byte media key
     * @return CompletableFuture completing when transmitted
     */
    public CompletableFuture<Void> sendMediaRetryReceipt(MessageReceiver.Decoded target, byte[] mediaKey) {
        return client.sendMediaRetryReceipt(target, mediaKey);
    }

    /**
     * Refreshes the WhatsApp media connection authentication credentials and routing hosts.
     *
     * @return CompletableFuture resolving to updated MediaConn
     */
    public CompletableFuture<MediaConn> refreshMediaConn() {
        return client.refreshMediaConn();
    }
}
