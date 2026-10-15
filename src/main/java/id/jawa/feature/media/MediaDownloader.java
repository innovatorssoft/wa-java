package id.jawa.feature.media;

import id.jawa.protocol.connection.*;
import id.jawa.protocol.codec.*;
import id.jawa.protocol.crypto.*;
import id.jawa.domain.model.*;
import id.jawa.domain.store.*;
import id.jawa.feature.pairing.*;
import id.jawa.feature.messaging.*;
import id.jawa.feature.media.*;
import id.jawa.feature.appstate.*;
import id.jawa.feature.signal.*;

import id.jawa.proto.Wa;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class MediaDownloader {

    private static final Logger LOG = LoggerFactory.getLogger(MediaDownloader.class);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    private MediaDownloader() {}

    public static byte[] download(Wa.Message msg) {
        if (msg == null) return null;
        while (msg != null) {
            if (msg.hasEphemeralMessage() && msg.getEphemeralMessage().hasMessage()) {
                msg = msg.getEphemeralMessage().getMessage();
            } else if (msg.hasViewOnceMessage() && msg.getViewOnceMessage().hasMessage()) {
                msg = msg.getViewOnceMessage().getMessage();
            } else if (msg.hasViewOnceMessageV2() && msg.getViewOnceMessageV2().hasMessage()) {
                msg = msg.getViewOnceMessageV2().getMessage();
            } else if (msg.hasDocumentWithCaptionMessage() && msg.getDocumentWithCaptionMessage().hasMessage()) {
                msg = msg.getDocumentWithCaptionMessage().getMessage();
            } else if (msg.hasDeviceSentMessage() && msg.getDeviceSentMessage().hasMessage()) {
                msg = msg.getDeviceSentMessage().getMessage();
            } else {
                break;
            }
        }
        if (msg == null) return null;

        if (msg.hasImageMessage()) {
            var img = msg.getImageMessage();
            String url = resolveUrl(img.getUrl(), img.getDirectPath());
            return fetchAndDecrypt(url, img.getMediaKey().toByteArray(), MediaCrypto.MediaType.IMAGE);
        }
        if (msg.hasVideoMessage()) {
            var vid = msg.getVideoMessage();
            String url = resolveUrl(vid.getUrl(), vid.getDirectPath());
            return fetchAndDecrypt(url, vid.getMediaKey().toByteArray(), MediaCrypto.MediaType.VIDEO);
        }
        if (msg.hasAudioMessage()) {
            var aud = msg.getAudioMessage();
            String url = resolveUrl(aud.getUrl(), aud.getDirectPath());
            return fetchAndDecrypt(url, aud.getMediaKey().toByteArray(), MediaCrypto.MediaType.AUDIO);
        }
        if (msg.hasDocumentMessage()) {
            var doc = msg.getDocumentMessage();
            String url = resolveUrl(doc.getUrl(), doc.getDirectPath());
            return fetchAndDecrypt(url, doc.getMediaKey().toByteArray(), MediaCrypto.MediaType.DOCUMENT);
        }
        if (msg.hasStickerMessage()) {
            var stk = msg.getStickerMessage();
            String url = resolveUrl(stk.getUrl(), stk.getDirectPath());
            return fetchAndDecrypt(url, stk.getMediaKey().toByteArray(), MediaCrypto.MediaType.IMAGE);
        }

        return null;
    }

    public static byte[] download(MessageReceiver.Decoded target) {
        if (target == null) return null;
        return download(target.message());
    }

    private static String resolveUrl(String url, String directPath) {
        if (url != null && !url.isBlank()) return url;
        if (directPath != null && !directPath.isBlank()) {
            return "https://mmg.whatsapp.net" + (directPath.startsWith("/") ? directPath : "/" + directPath);
        }
        return null;
    }

    public static byte[] downloadByUrl(String url, byte[] mediaKey, byte[] fileEncSha256, MediaCrypto.MediaType type) throws IOException, InterruptedException {
        return fetchAndDecrypt(url, mediaKey, type);
    }

    public static byte[] downloadByDirectPath(MediaConn conn, String directPath, byte[] mediaKey, byte[] fileEncSha256, MediaCrypto.MediaType type) throws IOException, InterruptedException {
        if (conn == null || conn.hosts().isEmpty()) {
            throw new IllegalArgumentException("MediaConn has no hosts");
        }
        String path = directPath.startsWith("/") ? directPath : "/" + directPath;
        for (String host : conn.hosts()) {
            String fullUrl = "https://" + host + path;
            byte[] res = fetchAndDecrypt(fullUrl, mediaKey, type);
            if (res != null) return res;
        }
        throw new IOException("Failed downloading media from all MediaConn hosts");
    }

    public static byte[] fetchAndDecrypt(String url, byte[] mediaKey, MediaCrypto.MediaType type) {
        if (url == null || url.isBlank() || mediaKey == null || mediaKey.length == 0) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

            HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                LOG.warn("Media HTTP download failed with status {}: {}", response.statusCode(), url);
                return null;
            }

            byte[] encBytes = response.body();
            return MediaCrypto.decrypt(encBytes, mediaKey, type);
        } catch (Exception e) {
            LOG.warn("Failed downloading and decrypting media from {}: {}", url, e.toString());
            return null;
        }
    }
}
