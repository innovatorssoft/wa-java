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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

public final class MediaUploader {

    private static final Logger LOG = LoggerFactory.getLogger(MediaUploader.class);

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    private static final Map<MediaCrypto.MediaType, String> MMS_TYPE = Map.of(
        MediaCrypto.MediaType.IMAGE,    "image",
        MediaCrypto.MediaType.VIDEO,    "video",
        MediaCrypto.MediaType.AUDIO,    "audio",
        MediaCrypto.MediaType.DOCUMENT, "document"
    );

    private MediaUploader() {}

    public record Result(String url, String directPath, String handle) {}

    public static Result upload(MediaConn mediaConn,
                                MediaCrypto.EncryptedMedia enc,
                                MediaCrypto.MediaType type) throws IOException, InterruptedException {
        if (mediaConn.hosts().isEmpty()) {
            throw new IllegalStateException("mediaConn has no hosts");
        }
        String host = mediaConn.hosts().get(0);
        String mmsType = MMS_TYPE.get(type);
        if (mmsType == null) throw new IllegalArgumentException("unsupported media type: " + type);

        String token = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(enc.fileEncSha256());
        String tokenForPath = URLEncoder.encode(token, StandardCharsets.UTF_8);
        String authForQuery = URLEncoder.encode(mediaConn.auth(), StandardCharsets.UTF_8);

        URI uri = URI.create("https://" + host + "/mms/" + mmsType + "/" + tokenForPath
            + "?auth=" + authForQuery + "&token=" + tokenForPath);

        HttpRequest req = HttpRequest.newBuilder(uri)
            .header("Origin", "https://web.whatsapp.com")
            .header("Referer", "https://web.whatsapp.com/")
            .header("Content-Type", "application/octet-stream")
            .timeout(Duration.ofMinutes(2))
            .POST(HttpRequest.BodyPublishers.ofByteArray(enc.uploadBytes()))
            .build();

        LOG.debug("Uploading {} bytes to {} (mmsType={})", enc.uploadBytes().length, host, mmsType);
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (resp.statusCode() != 200) {
            throw new IOException("media upload failed: HTTP " + resp.statusCode() + " — " + resp.body());
        }

        String body = resp.body();
        String url        = extractJsonString(body, "url");
        String directPath = extractJsonString(body, "direct_path");
        String handle     = extractJsonString(body, "handle");
        if (url == null || directPath == null) {
            throw new IOException("media upload response missing url/direct_path: " + body);
        }
        LOG.info("Uploaded {} bytes — url=[host]{}", enc.uploadBytes().length,
            directPath != null ? directPath : "");
        return new Result(url, directPath, handle);
    }

    private static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int openQuote = json.indexOf('"', colon + 1);
        if (openQuote < 0) return null;
        StringBuilder out = new StringBuilder();
        for (int i = openQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case '"' -> '"';
                    case '\\' -> '\\';
                    case '/' -> '/';
                    default -> next;
                });
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        return null;
    }

    public static void delete(MediaConn mediaConn, String directPath, byte[] encFileHash, MediaCrypto.MediaType type) throws IOException, InterruptedException {
        if (directPath == null || directPath.isBlank() || mediaConn == null || mediaConn.hosts().isEmpty()) {
            return;
        }
        String host = mediaConn.hosts().get(0);
        String mmsType = MMS_TYPE.get(type);
        if (mmsType == null) return;

        String path = directPath;
        int q = path.indexOf('?');
        if (q > 0) path = path.substring(0, q);

        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(encFileHash);
        String dMd = Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8));
        String auth = URLEncoder.encode(mediaConn.auth(), StandardCharsets.UTF_8);

        String url = "https://" + host + "/mms/" + mmsType + "/" + token + "?token=" + token + "&d_md=" + dMd + "&auth=" + auth;
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Origin", "https://web.whatsapp.com")
            .header("Referer", "https://web.whatsapp.com/")
            .DELETE()
            .build();
        HTTP.send(request, HttpResponse.BodyHandlers.discarding());
    }
}
