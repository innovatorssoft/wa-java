package id.jawa.feature.messaging;

import com.google.protobuf.ByteString;
import id.jawa.domain.model.AuthCreds;
import id.jawa.domain.model.Jid;
import id.jawa.feature.signal.JaWaProtocolStore;
import id.jawa.feature.signal.SessionBootstrap;
import id.jawa.proto.Wa;
import id.jawa.protocol.codec.BinaryNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.UntrustedIdentityException;
import org.whispersystems.libsignal.groups.GroupCipher;
import org.whispersystems.libsignal.groups.GroupSessionBuilder;
import org.whispersystems.libsignal.groups.SenderKeyName;
import org.whispersystems.libsignal.groups.state.SenderKeyStore;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.SenderKeyDistributionMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public final class GroupSender {

    private static final Logger LOG = LoggerFactory.getLogger(GroupSender.class);

    private GroupSender() {}

    public record Result(BinaryNode stanza,
                         Map<SignalProtocolAddress, Integer> ciphertextTypes,
                         int skdmRecipients,
                         String phash) {}

    public static String computeParticipantListHash(List<String> participantDeviceJids) {
        if (participantDeviceJids == null || participantDeviceJids.isEmpty()) {
            return "";
        }
        List<String> sorted = new ArrayList<>(participantDeviceJids);
        Collections.sort(sorted);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.join("", sorted).getBytes(StandardCharsets.UTF_8));
            byte[] prefix = Arrays.copyOfRange(hash, 0, 6);
            return "2:" + Base64.getUrlEncoder().withoutPadding().encodeToString(prefix);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Result buildStanza(JaWaProtocolStore protocolStore,
                                     SenderKeyStore senderKeyStore,
                                     AuthCreds creds,
                                     String msgId,
                                     String groupJid,
                                     List<String> participantDeviceJids,
                                     Wa.Message message) {
        Objects.requireNonNull(protocolStore);
        Objects.requireNonNull(senderKeyStore);
        Objects.requireNonNull(message);

        boolean useLid = false;
        if (creds.meLid != null && !creds.meLid.isEmpty()) {
            for (String dj : participantDeviceJids) {
                if (dj.endsWith("@lid")) {
                    useLid = true;
                    break;
                }
            }
        }
        Jid myJid = Jid.parse(useLid ? creds.meLid : creds.meJid);
        if (myJid == null) throw new IllegalStateException("creds.meJid/meLid invalid");
        SignalProtocolAddress myAddr = new SignalProtocolAddress(myJid.user(), myJid.device());

        SenderKeyName senderKeyName = new SenderKeyName(groupJid, myAddr);
        GroupSessionBuilder builder = new GroupSessionBuilder(senderKeyStore);
        SenderKeyDistributionMessage skdm = builder.create(senderKeyName);
        byte[] axoBytes = skdm.serialize();

        byte[] textPadded = MessageEncoder.encode(message);
        byte[] skmsgCiphertext;
        try {
            skmsgCiphertext = new GroupCipher(senderKeyStore, senderKeyName).encrypt(textPadded);
        } catch (NoSessionException e) {
            throw new IllegalStateException("group cipher encrypt failed", e);
        }

        Wa.Message skdmMessage = Wa.Message.newBuilder()
            .setSenderKeyDistributionMessage(Wa.Message.SenderKeyDistributionMessage.newBuilder()
                .setGroupId(groupJid)
                .setAxolotlSenderKeyDistributionMessage(ByteString.copyFrom(axoBytes))
                .build())
            .build();
        byte[] skdmPadded = MessageEncoder.encode(skdmMessage);

        List<BinaryNode> participants = new ArrayList<>();
        Map<SignalProtocolAddress, Integer> typeMap = new HashMap<>();
        boolean includeDeviceIdentity = false;

        for (String dj : participantDeviceJids) {
            Jid jid = Jid.parse(dj);
            if (jid == null) continue;
            SignalProtocolAddress addr = SessionBootstrap.addressFor(jid);
            if (addr.equals(myAddr)) continue;

            try {
                SessionCipher cipher = new SessionCipher(protocolStore, addr);
                CiphertextMessage ct = cipher.encrypt(skdmPadded);
                String type = ct.getType() == CiphertextMessage.PREKEY_TYPE ? "pkmsg" : "msg";
                if ("pkmsg".equals(type)) includeDeviceIdentity = true;
                typeMap.put(addr, ct.getType());

                BinaryNode enc = new BinaryNode("enc",
                    Map.of("v", "2", "type", type),
                    ct.serialize());
                BinaryNode to = new BinaryNode("to",
                    Map.of("jid", dj),
                    List.of(enc));
                participants.add(to);
            } catch (UntrustedIdentityException e) {
                LOG.warn("SKDM encrypt failed for {}: {}", dj, e.toString());
            }
        }

        String mediaType = getMediaTypeFromMessage(message);
        Map<String, String> encAttrs = new LinkedHashMap<>();
        encAttrs.put("v", "2");
        encAttrs.put("type", "skmsg");
        if (mediaType != null && !mediaType.isEmpty()) {
            encAttrs.put("mediatype", mediaType);
        }
        BinaryNode skmsgEnc = new BinaryNode("enc", encAttrs, skmsgCiphertext);

        List<BinaryNode> outer = new ArrayList<>();
        if (!participants.isEmpty()) {
            outer.add(new BinaryNode("participants", Map.of(), participants));
        }
        outer.add(skmsgEnc);
        if (includeDeviceIdentity && creds.account != null) {
            byte[] deviceIdentity = MessageSender.encodeDeviceIdentityForSend(creds.account);
            outer.add(new BinaryNode("device-identity", Map.of(), deviceIdentity));
        }
        BinaryNode biz = BizNode.buildIfNeeded(message);
        if (biz != null) outer.add(biz);

        String phash = computeParticipantListHash(participantDeviceJids);
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("to", groupJid);
        attrs.put("id", msgId);
        attrs.put("type", "text");
        if (!phash.isEmpty()) {
            attrs.put("phash", phash);
        }
        BinaryNode stanza = new BinaryNode("message", attrs, outer);

        return new Result(stanza, typeMap, participants.size(), phash);
    }

    public static String getMediaTypeFromMessage(Wa.Message msg) {
        if (msg == null) return null;
        if (msg.hasViewOnceMessage()) {
            return getMediaTypeFromMessage(msg.getViewOnceMessage().getMessage());
        }
        if (msg.hasViewOnceMessageV2()) {
            return getMediaTypeFromMessage(msg.getViewOnceMessageV2().getMessage());
        }
        if (msg.hasViewOnceMessageV2Extension()) {
            return getMediaTypeFromMessage(msg.getViewOnceMessageV2Extension().getMessage());
        }
        if (msg.hasDocumentWithCaptionMessage()) {
            return getMediaTypeFromMessage(msg.getDocumentWithCaptionMessage().getMessage());
        }
        if (msg.hasImageMessage()) return "image";
        if (msg.hasVideoMessage()) {
            return msg.getVideoMessage().getGifPlayback() ? "gif" : "video";
        }
        if (msg.hasAudioMessage()) {
            return msg.getAudioMessage().getPtt() ? "ptt" : "audio";
        }
        if (msg.hasDocumentMessage()) return "document";
        if (msg.hasStickerMessage()) return "sticker";
        if (msg.hasContactMessage()) return "vcard";
        if (msg.hasContactsArrayMessage()) return "contact_array";
        if (msg.hasListMessage()) return "list";
        if (msg.hasListResponseMessage()) return "list_response";
        if (msg.hasButtonsResponseMessage()) return "buttons_response";
        if (msg.hasOrderMessage()) return "order";
        if (msg.hasExtendedTextMessage() && msg.getExtendedTextMessage().hasTitle()) return "url";
        return null;
    }
}