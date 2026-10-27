package id.jawa.event;

import com.google.protobuf.ByteString;
import id.jawa.proto.Wa;
import id.jawa.protocol.codec.BinaryNode;
import id.jawa.protocol.crypto.AesGcm;
import id.jawa.protocol.crypto.Bytes;
import id.jawa.protocol.crypto.Hkdf;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds Baileys' media-retry re-upload request node.
 * <p>
 * Source: Baileys {@code encryptMediaRetryRequest} in
 * {@code lib/Utils/messages-media.js}: a {@code <receipt>} stanza
 * ({@code id} = message id, {@code to} = own bare JID,
 * {@code type} = {@code server-error}) carrying an
 * {@code <encrypt>} child ({@code enc_p}/{@code enc_iv}: a
 * {@code ServerErrorReceipt{stanzaId}} AES-GCM-encrypted under the
 * HKDF-derived media-retry key
 * {@code HKDF(mediaKey, 32, info='WhatsApp Media Retry Notification')}
 * with a random 12-byte IV and the message id as AAD) plus an
 * {@code <rmr>} child ({@code jid}, {@code from_me},
 * {@code participant} when a group message). Baileys notes the
 * encrypt node is "pretty useless" (the media is returned even
 * without it) but keeps it for WA Web parity; wa-java keeps it
 * for the same reason.
 * <p>
 * Increment 9: this builder is the single construction site shared
 * by {@code JaWaClient.sendMediaRetryReceipt} (pre-existing) and
 * {@code JaWaClient.updateMediaMessage} (new). It does not send
 * anything; the caller sends the returned node through the normal
 * send path, whose disconnected no-op behaviour is unchanged.
 */
public final class MediaRetryRequest {

    private MediaRetryRequest() {}

    /**
     * Builds the media-retry request node for a message.
     *
     * @param messageId message key id; must not be blank
     * @param remoteJid message key remote JID (chat JID)
     * @param fromMe message key fromMe
     * @param participant group participant, or null for DMs
     * @param mediaKey 32-byte media key of the message's media content; must not be null/empty
     * @param ownBareJid own bare JID for the receipt {@code to} attr; must not be blank
     * @return receipt node ready to send
     * @throws IllegalArgumentException when a required argument is missing
     */
    public static BinaryNode build(String messageId, String remoteJid, boolean fromMe,
                                   String participant, byte[] mediaKey, String ownBareJid) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        if (ownBareJid == null || ownBareJid.isBlank()) {
            throw new IllegalArgumentException("ownBareJid must not be blank");
        }
        if (mediaKey == null || mediaKey.length == 0) {
            throw new IllegalArgumentException("mediaKey must not be empty");
        }
        byte[] retryKey = Hkdf.derive(
            mediaKey, null, "WhatsApp Media Retry Notification".getBytes(StandardCharsets.UTF_8), 32);
        Wa.ServerErrorReceipt receipt = Wa.ServerErrorReceipt.newBuilder()
            .setStanzaId(messageId)
            .build();
        byte[] iv = Bytes.random(12);
        byte[] ciphertext = AesGcm.encrypt(
            retryKey, iv, receipt.toByteArray(), messageId.getBytes(StandardCharsets.UTF_8));

        Map<String, String> rmrAttrs = new LinkedHashMap<>();
        rmrAttrs.put("jid", remoteJid);
        rmrAttrs.put("from_me", String.valueOf(fromMe));
        if (participant != null && !participant.isBlank()) {
            rmrAttrs.put("participant", participant);
        }

        List<BinaryNode> encryptChildren = List.of(
            new BinaryNode("enc_p", Map.of(), ciphertext),
            new BinaryNode("enc_iv", Map.of(), iv));

        Map<String, String> receiptAttrs = new LinkedHashMap<>();
        receiptAttrs.put("id", messageId);
        receiptAttrs.put("to", ownBareJid);
        receiptAttrs.put("type", "server-error");

        return new BinaryNode("receipt", receiptAttrs,
            List.of(new BinaryNode("encrypt", Map.of(), encryptChildren),
                new BinaryNode("rmr", rmrAttrs, null)));
    }

    /**
     * Extracts the media key from a message's media content, mirroring
     * Baileys' {@code assertMediaContent} + {@code content.mediaKey}
     * ({@code lib/Socket/messages-send.js:1070} area) for the media
     * types wa-java's media path already handles (image, video,
     * audio, document, sticker — see {@code MediaDownloader}).
     * <p>
     * Divergence, stated not hidden: Baileys re-uploads from the
     * original media bytes held by the caller; wa-java cannot recover
     * those bytes from a {@link Wa.Message} alone, so
     * {@code updateMediaMessage} only issues the retry request and
     * returns the phone's re-uploaded location — it never re-uploads
     * bytes itself, stale or otherwise.
     *
     * @param message message to scan; null returns null
     * @return media key bytes, or null when the message carries no media key
     */
    public static byte[] mediaKeyOf(Wa.Message message) {
        if (message == null) return null;
        ByteString key = null;
        if (message.hasImageMessage()) {
            key = message.getImageMessage().getMediaKey();
        } else if (message.hasVideoMessage()) {
            key = message.getVideoMessage().getMediaKey();
        } else if (message.hasAudioMessage()) {
            key = message.getAudioMessage().getMediaKey();
        } else if (message.hasDocumentMessage()) {
            key = message.getDocumentMessage().getMediaKey();
        } else if (message.hasStickerMessage()) {
            key = message.getStickerMessage().getMediaKey();
        }
        return key == null || key.isEmpty() ? null : key.toByteArray();
    }
}
