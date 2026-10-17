package id.jawa.feature.messaging;


import com.google.protobuf.InvalidProtocolBufferException;
import id.jawa.domain.model.AuthCreds;
import id.jawa.domain.model.Jid;
import id.jawa.feature.signal.JaWaProtocolStore;
import id.jawa.feature.signal.SessionBootstrap;
import id.jawa.proto.Wa;
import id.jawa.protocol.codec.BinaryNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.UntrustedIdentityException;
import org.whispersystems.libsignal.protocol.CiphertextMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MessageSender {

    private static final Logger LOG = LoggerFactory.getLogger(MessageSender.class);

    private MessageSender() {}

    public static Result buildStanza(JaWaProtocolStore store,
                                     AuthCreds creds,
                                     String msgId,
                                     String recipientBareJid,
                                     List<String> deviceJids,
                                     Wa.Message message) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(creds);
        Objects.requireNonNull(message);

        Jid myJid = Jid.parse(recipientBareJid.endsWith("@lid") && creds.meLid != null && !creds.meLid.isBlank() ? creds.meLid : creds.meJid);
        if (myJid == null) throw new IllegalStateException("creds.meJid/meLid invalid");
        SignalProtocolAddress myAddr = new SignalProtocolAddress(myJid.user(), myJid.device());
        String ownUser = myJid.user();

        byte[] msgPadded = MessageEncoder.encode(message);
        byte[] dsmPadded = null;

        List<BinaryNode> participants = new ArrayList<>();
        Map<SignalProtocolAddress, Integer> typeMap = new HashMap<>();
        boolean includeDeviceIdentity = false;

        for (String dj : deviceJids) {
            Jid jid = Jid.parse(dj);
            if (jid == null) continue;
            SignalProtocolAddress addr = SessionBootstrap.addressFor(jid);
            if (addr.equals(myAddr)) continue;

            byte[] plaintext;
            if (jid.user().equals(ownUser)) {
                if (dsmPadded == null) {
                    dsmPadded = MessageEncoder.encode(
                        MessageEncoder.deviceSent(recipientBareJid, message));
                }
                plaintext = dsmPadded;
            } else {
                plaintext = msgPadded;
            }

            try {
                SessionCipher cipher = new SessionCipher(store, addr);
                CiphertextMessage ct = cipher.encrypt(plaintext);
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
                LOG.warn("Encrypt failed for {}: {}", dj, e.toString());
            }
        }

        if (participants.isEmpty()) {
            throw new IllegalStateException("No recipients could be encrypted for; "
                + "did you bootstrap sessions first?");
        }

        List<BinaryNode> outer = new ArrayList<>();
        outer.add(new BinaryNode("participants", Map.of(), participants));
        if (includeDeviceIdentity && creds.account != null) {

            byte[] deviceIdentity = encodeDeviceIdentityForSend(creds.account);
            outer.add(new BinaryNode("device-identity", Map.of(), deviceIdentity));
        }
        BinaryNode biz = BizNode.buildIfNeeded(message);
        if (biz != null) outer.add(biz);

        Map<String, String> attrs = new HashMap<>();
        attrs.put("to", recipientBareJid);
        attrs.put("id", msgId);
        attrs.put("type", "text");
        if (recipientBareJid.endsWith("@lid")) {
            attrs.put("addressing_mode", "lid");
        }
        BinaryNode stanza = new BinaryNode("message", attrs, outer);

        return new Result(stanza, typeMap);
    }

    public static Result buildDualIdentityStanza(JaWaProtocolStore store,
                                                  AuthCreds creds,
                                                  String msgId,
                                                  String recipientBareJid,
                                                  List<String> wireDeviceJids,
                                                  List<String> encryptionDeviceJids,
                                                  Wa.Message message) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(creds);
        Objects.requireNonNull(message);

        Jid myJid = Jid.parse(creds.meJid);
        if (myJid == null) throw new IllegalStateException("creds.meJid invalid");
        SignalProtocolAddress myAddr = new SignalProtocolAddress(myJid.user(), myJid.device());
        String ownUser = myJid.user();

        String ownLidUser = null;
        if (creds.meLid != null && !creds.meLid.isBlank()) {
            Jid lidJid = Jid.parse(creds.meLid);
            if (lidJid != null) ownLidUser = lidJid.user();
        }

        byte[] msgPadded = MessageEncoder.encode(message);
        byte[] dsmPadded = null;

        List<BinaryNode> participants = new ArrayList<>();
        Map<SignalProtocolAddress, Integer> typeMap = new HashMap<>();
        boolean includeDeviceIdentity = false;

        for (int i = 0; i < wireDeviceJids.size(); i++) {
            String wireJid = wireDeviceJids.get(i);
            String encJid = (i < encryptionDeviceJids.size()) ? encryptionDeviceJids.get(i) : wireJid;

            Jid wireParsed = Jid.parse(wireJid);
            Jid encParsed = Jid.parse(encJid);
            if (wireParsed == null || encParsed == null) continue;

            SignalProtocolAddress encAddr = SessionBootstrap.addressFor(encParsed);
            if (encAddr.equals(myAddr)) continue;

            if (ownLidUser != null && encParsed.user().equals(ownLidUser)
                    && encParsed.device() == myJid.device()) continue;

            byte[] plaintext;
            boolean isOwnDevice = wireParsed.user().equals(ownUser)
                    || (ownLidUser != null && encParsed.user().equals(ownLidUser));
            if (isOwnDevice) {
                if (dsmPadded == null) {
                    dsmPadded = MessageEncoder.encode(
                        MessageEncoder.deviceSent(recipientBareJid, message));
                }
                plaintext = dsmPadded;
            } else {
                plaintext = msgPadded;
            }

            try {
                SessionCipher cipher = new SessionCipher(store, encAddr);
                CiphertextMessage ct = cipher.encrypt(plaintext);
                String type = ct.getType() == CiphertextMessage.PREKEY_TYPE ? "pkmsg" : "msg";
                if ("pkmsg".equals(type)) includeDeviceIdentity = true;
                typeMap.put(encAddr, ct.getType());

                BinaryNode enc = new BinaryNode("enc",
                    Map.of("v", "2", "type", type),
                    ct.serialize());

                BinaryNode to = new BinaryNode("to",
                    Map.of("jid", wireJid),
                    List.of(enc));
                participants.add(to);
            } catch (UntrustedIdentityException e) {
                LOG.warn("Encrypt failed for {} (enc={}): {}", wireJid, encJid, e.toString());
            }
        }

        if (participants.isEmpty()) {
            throw new IllegalStateException("No recipients could be encrypted for; "
                + "did you bootstrap sessions first?");
        }

        List<BinaryNode> outer = new ArrayList<>();
        outer.add(new BinaryNode("participants", Map.of(), participants));
        if (includeDeviceIdentity && creds.account != null) {
            byte[] deviceIdentity = encodeDeviceIdentityForSend(creds.account);
            outer.add(new BinaryNode("device-identity", Map.of(), deviceIdentity));
        }
        BinaryNode biz = BizNode.buildIfNeeded(message);
        if (biz != null) outer.add(biz);

        Map<String, String> attrs = new HashMap<>();
        attrs.put("to", recipientBareJid);
        attrs.put("id", msgId);
        attrs.put("type", "text");
        if (recipientBareJid.endsWith("@lid")) {
            attrs.put("addressing_mode", "lid");
        }
        BinaryNode stanza = new BinaryNode("message", attrs, outer);

        return new Result(stanza, typeMap);
    }

    static byte[] encodeDeviceIdentityForSend(byte[] fullAccountBytes) {
        try {
            Wa.ADVSignedDeviceIdentity full = Wa.ADVSignedDeviceIdentity.parseFrom(fullAccountBytes);
            Wa.ADVSignedDeviceIdentity.Builder b = Wa.ADVSignedDeviceIdentity.newBuilder()
                .setDetails(full.getDetails())
                .setAccountSignature(full.getAccountSignature());
            if (full.hasDeviceSignature()) b.setDeviceSignature(full.getDeviceSignature());

            return b.build().toByteArray();
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("invalid creds.account proto", e);
        }
    }

    public record Result(BinaryNode stanza, Map<SignalProtocolAddress, Integer> ciphertextTypes) {}
}
