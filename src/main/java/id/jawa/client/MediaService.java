// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.client;

import id.jawa.feature.media.MediaConn;
import id.jawa.feature.media.MediaCrypto;
import id.jawa.proto.Wa;

import java.util.concurrent.CompletableFuture;

/**
 * Service facade handling media operations in JaWa (sending images, videos, audio/voice notes,
 * documents, view-once media, media downloads, and media conn refreshing).
 */
public final class MediaService {

    private final JaWaClient client;

    MediaService(JaWaClient client) {
        this.client = client;
    }

    /** Download and decrypt media bytes from a received Protobuf message. */
    public byte[] downloadMedia(Wa.Message msg) {
        return client.downloadMedia(msg);
    }

    /** Asynchronously download and decrypt media bytes using url/directPath, mediaKey, and MediaType. */
    public CompletableFuture<byte[]> downloadMedia(String url, String directPath, byte[] mediaKey,
                                                   byte[] fileEncSha256, MediaCrypto.MediaType type) {
        return client.downloadMedia(url, directPath, mediaKey, fileEncSha256, type);
    }

    /** Send an image message. */
    public CompletableFuture<String> sendImage(String chatJid, byte[] imageBytes, String mimetype, String caption) {
        return client.sendImage(chatJid, imageBytes, mimetype, caption);
    }

    /** Send a JPEG image message with default mimetype "image/jpeg". */
    public CompletableFuture<String> sendImage(String chatJid, byte[] imageBytes, String caption) {
        return client.sendImage(chatJid, imageBytes, "image/jpeg", caption);
    }

    /** Send a view-once image message. */
    public CompletableFuture<String> sendImageViewOnce(String chatJid, byte[] imageBytes, String mimetype, String caption) {
        return client.sendImageViewOnce(chatJid, imageBytes, mimetype, caption);
    }

    /** Send a video message. */
    public CompletableFuture<String> sendVideo(String chatJid, byte[] videoBytes, String mimetype, String caption,
                                              int seconds, int width, int height) {
        return client.sendVideo(chatJid, videoBytes, mimetype, caption, seconds, width, height);
    }

    /** Send a video message with default dimensions (0, 0, 0). */
    public CompletableFuture<String> sendVideo(String chatJid, byte[] videoBytes, String mimetype, String caption) {
        return client.sendVideo(chatJid, videoBytes, mimetype, caption, 0, 0, 0);
    }

    /** Send a view-once video message. */
    public CompletableFuture<String> sendVideoViewOnce(String chatJid, byte[] videoBytes, String mimetype, String caption,
                                                      int seconds, int width, int height) {
        return client.sendVideoViewOnce(chatJid, videoBytes, mimetype, caption, seconds, width, height);
    }

    /** Send an audio message (PTT / Voice Note if ptt is true). */
    public CompletableFuture<String> sendAudio(String chatJid, byte[] audioBytes, String mimetype, int seconds, boolean ptt) {
        return client.sendAudio(chatJid, audioBytes, mimetype, seconds, ptt);
    }

    /** Send a view-once audio message. */
    public CompletableFuture<String> sendAudioViewOnce(String chatJid, byte[] audioBytes, String mimetype, int seconds, boolean ptt) {
        return client.sendAudioViewOnce(chatJid, audioBytes, mimetype, seconds, ptt);
    }

    /** Send a document/file attachment message. */
    public CompletableFuture<String> sendDocument(String chatJid, byte[] docBytes, String mimetype, String fileName, String title) {
        return client.sendDocument(chatJid, docBytes, mimetype, fileName, title);
    }

    /** Refresh media upload connection credentials from WhatsApp servers. */
    public CompletableFuture<MediaConn> refreshMediaConn() {
        return client.refreshMediaConn();
    }
}
