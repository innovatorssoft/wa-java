package id.ja_wa.feature.media;


import id.ja_wa.protocol.crypto.Sha256;
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

/**
 * Handles uploading encrypted media payloads to WhatsApp MMS CDN servers.
 * <p>
 * Supports auth bearer token dispatch, chunk streaming, and direct path resolution.
 */
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

    /**
     * Increment 2: Baileys' {@code NEWSLETTER_MEDIA_PATH_MAP} (fork): the
     * upload path switches from {@code /mms/<type>} to
     * {@code /newsletter/newsletter-<type>} for newsletter media, and the
     * query gains {@code server_thumb_gen=1}. There is no {@code /m1/} or
     * {@code /o1/} host logic in Baileys — the path map is the mechanism.
     * Stickers map to the image path, as in both Baileys maps (wa-java
     * uploads stickers as {@code MediaType.IMAGE}).
     */
    private static final Map<MediaCrypto.MediaType, String> NEWSLETTER_TYPE = Map.of(
        MediaCrypto.MediaType.IMAGE,    "newsletter-image",
        MediaCrypto.MediaType.VIDEO,    "newsletter-video",
        MediaCrypto.MediaType.AUDIO,    "newsletter-audio",
        MediaCrypto.MediaType.DOCUMENT, "newsletter-document"
    );

    /**
     * Increment 5: Baileys' {@code MEDIA_PATH_MAP}
     * ({@code lib/Defaults/index.js}) paths for the two media types
     * whose uploads are <em>raw</em> (unencrypted) rather than MMS
     * media: product-catalog images ({@code /product/image}) and
     * business cover photos ({@code /pps/biz-cover-photo}). They are
     * not MMS types, so they are deliberately not added to
     * {@link #MMS_TYPE}/{@link #NEWSLETTER_TYPE}: {@link #upload} still
     * rejects them, and {@link #uploadRaw} serves them instead.
     */
    public static final String PRODUCT_IMAGE_PATH = "/product/image";
    public static final String BIZ_COVER_PHOTO_PATH = "/pps/biz-cover-photo";

    /** Default media host Baileys' {@code getUrlFromDirectPath} uses. */
    public static final String DEFAULT_MEDIA_HOST = "mmg.whatsapp.net";

    private MediaUploader() {}

    public record Result(String url, String directPath, String handle) {}

    /**
     * Result of a raw upload (Baileys {@code waUploadToServer}).
     * {@code metaHmac}, {@code fbid} and {@code ts} are only populated
     * by uploads that return them (business cover photos); product
     * image uploads populate {@code url}/{@code directPath} only.
     */
    public record RawUploadResult(String url, String directPath, String metaHmac,
                                  String fbid, Long ts) {}

    /**
     * Builds the upload URL for an encrypted media payload, without sending
     * anything. Pure (no network) so it is unit-testable; {@link #upload}
     * uses exactly this URL.
     *
     * @param host upload host (first media-conn host)
     * @param auth media-conn auth token
     * @param fileEncSha256 encrypted file hash bytes
     * @param type media type
     * @param newsletter true for newsletter uploads (newsletter path map
     *                   plus {@code server_thumb_gen=1})
     * @return upload URL string
     * @throws IllegalArgumentException for an unsupported media type
     */
    public static String uploadUrl(String host, String auth, byte[] fileEncSha256,
                                   MediaCrypto.MediaType type, boolean newsletter) {
        String pathType = (newsletter ? NEWSLETTER_TYPE : MMS_TYPE).get(type);
        if (pathType == null) throw new IllegalArgumentException("unsupported media type: " + type);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(fileEncSha256);
        String tokenForPath = URLEncoder.encode(token, StandardCharsets.UTF_8);
        String authForQuery = URLEncoder.encode(auth, StandardCharsets.UTF_8);
        String prefix = newsletter ? "/newsletter/" : "/mms/";
        String url = "https://" + host + prefix + pathType + "/" + tokenForPath
            + "?auth=" + authForQuery + "&token=" + tokenForPath;
        if (newsletter) url += "&server_thumb_gen=1";
        return url;
    }

    /**
     * Builds the upload URL for a raw (unencrypted) upload, without
     * sending anything. Pure (no network) so it is unit-testable;
     * {@link #uploadRaw} uses exactly this URL.
     * <p>Baileys' {@code getWAUploadToServer}
     * ({@code lib/Utils/messages-media.js}) builds
     * {@code https://<host><MEDIA_PATH_MAP[mediaType]>/<token>?auth=<auth>&token=<token>}
     * where the token is the base64url form of the file hash — the
     * same token convention {@link #uploadUrl} already uses for MMS
     * uploads. One honesty note: the compiled Baileys tree redacts
     * the token query value at that line
     * ({@code &token=<redacted>}), so the token-equals-path-token
     * shape is taken from wa-java's existing MMS convention, not
     * re-verified from that redacted line; a live upload against a
     * real media host is still the final confirmation for this path.
     *
     * @param host upload host (first media-conn host)
     * @param auth media-conn auth token
     * @param fileSha256 SHA-256 of the raw (unencrypted) bytes
     * @param mediaPath Baileys media path, e.g. {@link #PRODUCT_IMAGE_PATH}
     * @return upload URL string
     */
    public static String rawUploadUrl(String host, String auth, byte[] fileSha256,
                                      String mediaPath) {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(fileSha256);
        String tokenForPath = URLEncoder.encode(token, StandardCharsets.UTF_8);
        String authForQuery = URLEncoder.encode(auth, StandardCharsets.UTF_8);
        return "https://" + host + mediaPath + "/" + tokenForPath
            + "?auth=" + authForQuery + "&token=" + tokenForPath;
    }

    /**
     * Baileys' {@code getUrlFromDirectPath}
     * ({@code lib/Utils/messages-media.js}): the public URL for a
     * direct path, on the default media host.
     *
     * @param directPath direct path, with or without a leading slash
     * @return {@code https://mmg.whatsapp.net<directPath>}, or null
     *         when {@code directPath} is null or blank
     */
    public static String urlFromDirectPath(String directPath) {
        if (directPath == null || directPath.isBlank()) return null;
        return "https://" + DEFAULT_MEDIA_HOST
            + (directPath.startsWith("/") ? directPath : "/" + directPath);
    }

    /**
     * Baileys' {@code uploadingNecessaryImages} hosted check
     * ({@code lib/Utils/business.js}): an image whose URL already
     * contains {@code .whatsapp.net} is already uploaded and must be
     * passed through untouched, never re-uploaded.
     */
    public static boolean isWhatsAppHostedUrl(String url) {
        return url != null && url.contains(".whatsapp.net");
    }

    /**
     * Uploads raw (unencrypted) bytes to a Baileys media path, as
     * Baileys' {@code waUploadToServer} does for product-catalog
     * images and business cover photos: the file is posted as-is
     * (no {@code MediaCrypto} encryption — these types are not MMS
     * media), the token is the SHA-256 of the raw bytes, and the
     * response JSON supplies {@code url}/{@code direct_path} plus,
     * for cover photos, {@code meta_hmac}/{@code fbid}/{@code ts}.
     * Only the first media-conn host is tried, matching
     * {@link #upload}'s convention (Baileys retries across hosts;
     * that retry loop is not replicated here).
     *
     * @param mediaConn media connection (first host is used)
     * @param rawBytes raw file bytes to upload
     * @param mediaPath Baileys media path, e.g. {@link #PRODUCT_IMAGE_PATH}
     * @return upload result
     * @throws IOException when the upload fails or the response
     *         carries neither a URL nor a direct path
     */
    public static RawUploadResult uploadRaw(MediaConn mediaConn, byte[] rawBytes,
                                            String mediaPath)
            throws IOException, InterruptedException {
        if (mediaConn == null || mediaConn.hosts().isEmpty()) {
            throw new IllegalStateException("mediaConn has no hosts");
        }
        if (rawBytes == null) throw new IllegalArgumentException("rawBytes must not be null");
        if (mediaPath == null || mediaPath.isBlank()) {
            throw new IllegalArgumentException("mediaPath must not be blank");
        }
        byte[] fileSha256 = Sha256.hash(rawBytes);
        String host = mediaConn.hosts().get(0);
        URI uri = URI.create(rawUploadUrl(host, mediaConn.auth(), fileSha256, mediaPath));

        HttpRequest req = HttpRequest.newBuilder(uri)
            .header("Origin", "https://web.whatsapp.com")
            .header("Referer", "https://web.whatsapp.com/")
            .header("Content-Type", "application/octet-stream")
            .timeout(Duration.ofMinutes(2))
            .POST(HttpRequest.BodyPublishers.ofByteArray(rawBytes))
            .build();

        LOG.debug("Raw-uploading {} bytes to {} ({})", rawBytes.length, host, mediaPath);
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (resp.statusCode() != 200) {
            throw new IOException("raw media upload failed: HTTP " + resp.statusCode() + ": " + resp.body());
        }
        String body = resp.body();
        String url = extractJsonString(body, "url");
        String directPath = extractJsonString(body, "direct_path");
        if (url == null && directPath == null) {
            throw new IOException("raw media upload response missing url/direct_path: " + body);
        }
        String metaHmac = extractJsonString(body, "meta_hmac");
        String fbid = extractJsonString(body, "fbid");
        if (fbid == null) {
            Long fbidNum = extractJsonNumber(body, "fbid");
            fbid = fbidNum == null ? null : String.valueOf(fbidNum);
        }
        Long ts = extractJsonNumber(body, "ts");
        if (ts == null) {
            String tsStr = extractJsonString(body, "ts");
            if (tsStr != null) {
                try { ts = Long.parseLong(tsStr); } catch (NumberFormatException ignored) {
                    // leave ts null; the caller validates what it needs
                }
            }
        }
        return new RawUploadResult(url, directPath, metaHmac, fbid, ts);
    }

    /**
     * Baileys {@code uploadingNecessaryImages}
     * ({@code lib/Utils/business.js}) for one product image: raw-upload
     * the bytes as {@code product-catalog-image} and return the public
     * URL Baileys returns for it (built from {@code directPath}, not
     * from the response {@code url}).
     *
     * @param mediaConn media connection (first host is used)
     * @param imageBytes raw image bytes
     * @return public {@code .whatsapp.net} URL for the uploaded image
     * @throws IOException when the upload fails or returns no direct path
     */
    public static String uploadProductImage(MediaConn mediaConn, byte[] imageBytes)
            throws IOException, InterruptedException {
        RawUploadResult result = uploadRaw(mediaConn, imageBytes, PRODUCT_IMAGE_PATH);
        String url = urlFromDirectPath(result.directPath());
        if (url == null) {
            throw new IOException("product image upload response missing direct_path");
        }
        return url;
    }

    /**
     * Baileys {@code updateCoverPhoto} upload step
     * ({@code lib/Socket/business.js}): raw-upload the photo as
     * {@code biz-cover-photo}. The caller (see
     * {@code ProfileService}) sends the delta IQ with the returned
     * {@code fbid}/{@code metaHmac}/{@code ts}.
     *
     * @param mediaConn media connection (first host is used)
     * @param photoBytes raw cover-photo bytes
     * @return upload result carrying {@code fbid}/{@code metaHmac}/{@code ts}
     * @throws IOException when the upload fails
     */
    public static RawUploadResult uploadBizCoverPhoto(MediaConn mediaConn, byte[] photoBytes)
            throws IOException, InterruptedException {
        return uploadRaw(mediaConn, photoBytes, BIZ_COVER_PHOTO_PATH);
    }

    public static Result upload(MediaConn mediaConn,
                                MediaCrypto.EncryptedMedia enc,
                                MediaCrypto.MediaType type) throws IOException, InterruptedException {
        return upload(mediaConn, enc, type, false);
    }

    /**
     * Uploads encrypted media, optionally via the newsletter path map
     * (Increment 2). See {@link #uploadUrl} for the URL shape.
     *
     * @param mediaConn media connection (first host is used)
     * @param enc encrypted media payload
     * @param type media type
     * @param newsletter true for newsletter uploads
     * @return upload result with url, direct path and handle
     */
    public static Result upload(MediaConn mediaConn,
                                MediaCrypto.EncryptedMedia enc,
                                MediaCrypto.MediaType type,
                                boolean newsletter) throws IOException, InterruptedException {
        if (mediaConn.hosts().isEmpty()) {
            throw new IllegalStateException("mediaConn has no hosts");
        }
        String host = mediaConn.hosts().get(0);
        String mmsType = (newsletter ? NEWSLETTER_TYPE : MMS_TYPE).get(type);
        if (mmsType == null) throw new IllegalArgumentException("unsupported media type: " + type);

        URI uri = URI.create(uploadUrl(host, mediaConn.auth(), enc.fileEncSha256(), type, newsletter));

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
            throw new IOException("media upload failed: HTTP " + resp.statusCode() + ": " + resp.body());
        }

        String body = resp.body();
        String url        = extractJsonString(body, "url");
        String directPath = extractJsonString(body, "direct_path");
        String handle     = extractJsonString(body, "handle");
        if (url == null || directPath == null) {
            throw new IOException("media upload response missing url/direct_path: " + body);
        }
        LOG.info("Uploaded {} bytes: url=[host]{}", enc.uploadBytes().length,
            directPath != null ? directPath : "");
        return new Result(url, directPath, handle);
    }

    /** Extracts a bare (unquoted) JSON number value for {@code key}, or null. */
    private static Long extractJsonNumber(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) == '"') return null;
        int start = i;
        while (i < json.length()
            && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-' || json.charAt(i) == '.')) i++;
        if (start == i) return null;
        try {
            return (long) Double.parseDouble(json.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
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
