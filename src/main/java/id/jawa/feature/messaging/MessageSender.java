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

    private static String resolveDsmDestination(String recipientBareJid, AuthCreds creds) {
        if (recipientBareJid != null && recipientBareJid.endsWith("@lid") && creds.meLid != null && !creds.meLid.isBlank()) {
            Jid lidJid = Jid.parse(creds.meLid);
            if (lidJid != null && recipientBareJid.startsWith(lidJid.user())) {
                Jid myPn = Jid.parse(creds.meJid);
                if (myPn != null) {
                    return myPn.user() + "@" + Jid.SERVER_WHATSAPP;
                }
            }
        }
        return recipientBareJid;
    }

    private MessageSender() {}

    public static Result buildStanza(JaWaProtocolStore store,
                                     AuthCreds creds,
                                     String msgId,
                                     String recipientBareJid,
                                     List<String> deviceJids,
                                     Wa.Message message) {
        return buildStanza(store, creds, msgId, recipientBareJid, deviceJids, message, List.of());
    }

    /**
     * Builds a DM stanza, appending {@code additionalNodes} (if any) as
     * extra top-level children after the {@code participants} /
     * {@code device-identity} / {@code biz} nodes. This is Baileys'
     * {@code relayMessage} {@code additionalNodes} mechanism
     * ({@code lib/Socket/messages-send.js}): it carries the
     * {@code <meta>} nodes for status mentions
     * ({@code mentioned_users}, {@code is_status_mention} /
     * {@code is_group_status_mention}) without changing the encryption
     * or addressing of the stanza itself.
     *
     * @param additionalNodes extra stanza children, appended in order; null is treated as empty
     */
    public static Result buildStanza(JaWaProtocolStore store,
                                     AuthCreds creds,
                                     String msgId,
                                     String recipientBareJid,
                                     List<String> deviceJids,
                                     Wa.Message message,
                                     List<BinaryNode> additionalNodes) {
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
                        MessageEncoder.deviceSent(resolveDsmDestination(recipientBareJid, creds), message));
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
        if (additionalNodes != null) outer.addAll(additionalNodes);

        Map<String, String> attrs = new HashMap<>();
        attrs.put("to", recipientBareJid);
        attrs.put("id", msgId);
        attrs.put("type", "text");
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
        return buildDualIdentityStanza(store, creds, msgId, recipientBareJid,
            wireDeviceJids, encryptionDeviceJids, message, List.of());
    }

    /**
     * Dual-identity variant of {@link #buildStanza(JaWaProtocolStore, AuthCreds, String, String, List, Wa.Message, List)},
     * appending {@code additionalNodes} as extra top-level children after
     * the {@code participants} / {@code device-identity} / {@code biz}
     * nodes (Baileys' {@code relayMessage} {@code additionalNodes}).
     *
     * @param additionalNodes extra stanza children, appended in order; null is treated as empty
     */
    public static Result buildDualIdentityStanza(JaWaProtocolStore store,
                                                  AuthCreds creds,
                                                  String msgId,
                                                  String recipientBareJid,
                                                  List<String> wireDeviceJids,
                                                  List<String> encryptionDeviceJids,
                                                  Wa.Message message,
                                                  List<BinaryNode> additionalNodes) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(creds);
        Objects.requireNonNull(message);

        Jid myJid = Jid.parse(recipientBareJid.endsWith("@lid") && creds.meLid != null && !creds.meLid.isBlank() ? creds.meLid : creds.meJid);
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
                        MessageEncoder.deviceSent(resolveDsmDestination(recipientBareJid, creds), message));
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
        if (additionalNodes != null) outer.addAll(additionalNodes);

        Map<String, String> attrs = new HashMap<>();
        attrs.put("to", recipientBareJid);
        attrs.put("id", msgId);
        attrs.put("type", "text");
        BinaryNode stanza = new BinaryNode("message", attrs, outer);

        return new Result(stanza, typeMap);
    }

    /**
     * Returns a copy of a message stanza with {@code additionalNodes}
     * appended as top-level children, in order. Used by
     * {@link GroupSender} for its additional-nodes overload, and
     * directly testable without encryption. A stanza whose content is
     * not a child list, or an empty/null node list, is returned
     * unchanged; the original stanza is never mutated.
     *
     * @param stanza message stanza whose children are extended
     * @param additionalNodes extra children to append
     * @return new stanza with the appended children
     */
    static BinaryNode withAdditionalNodes(BinaryNode stanza, List<BinaryNode> additionalNodes) {
        if (stanza == null || additionalNodes == null || additionalNodes.isEmpty()
                || !(stanza.content() instanceof List)) {
            return stanza;
        }
        List<BinaryNode> kids = new ArrayList<>(stanza.childrenList());
        kids.addAll(additionalNodes);
        return new BinaryNode(stanza.tag(), stanza.attrs(), kids);
    }

    /**
     * Returns a copy of a message stanza with {@code additionalAttributes}
     * merged onto its attributes, in order. Used by the Increment 6
     * additional-attributes DM overload for Baileys'
     * {@code relayMessage} {@code additionalAttributes} (e.g. the PDO
     * {@code category}/{@code push_priority} attributes, which Baileys
     * spreads over the base {@code id}/{@code to}/{@code type}
     * attributes, so supplied attributes win on conflict). A null or
     * empty attribute map returns the stanza unchanged; the original
     * stanza is never mutated.
     *
     * @param stanza message stanza whose attributes are extended
     * @param additionalAttributes attributes to merge onto the stanza
     * @return new stanza with the merged attributes
     */
    /**
     * Increment 8: builds the single-device peer stanza input for
     * Baileys' peer relay ({@code lib/Socket/messages-send.js}:
     * {@code relayMessage} with {@code category: 'peer'} skips USync
     * device enumeration, so its device list is just the own
     * device-0 placeholder — {@code jidEncode(user, server, 0)},
     * which omits the device component, i.e. the bare own JID whose
     * Signal address is {@code (user, 0)}). Unlike
     * {@link #buildStanza} / {@link #buildDualIdentityStanza}, this
     * method does <em>not</em> skip the sender's own address: the
     * whole point is to encrypt for device 0, and the sender's own
     * Signal address is the companion device (non-zero) in the live
     * peer case, so device 0 is a distinct recipient. The plaintext
     * is the device-sent (DSM) encoding, as for every own device in
     * the DM builders, and the result carries a
     * {@code <participants>} wrapper holding the single device-0
     * {@code <to>} node so that {@link #peerFrame} can reframe it
     * into Baileys' bare-{@code <enc>} peer framing. No existing
     * builder is changed: DM and group sends never call this method.
     * Callers must ensure a session exists for device 0 first
     * (e.g. via {@code fetchBundlesAndInstallSessions}); when none
     * exists the underlying {@code SessionCipher} throws, exactly as
     * the DM builders throw when no recipient can be encrypted for.
     * When the client's own device <em>is</em> 0 (a bare
     * {@code creds.meJid}), device 0 is the sender itself and this
     * method must not be used — callers fall back to the self-DM
     * path instead (see
     * {@code JaWaClient.sendPeerDataOperationMessage}).
     *
     * @param store protocol store holding the device-0 session
     * @param creds account creds; {@code meJid} supplies the own user/server
     * @param msgId stanza id
     * @param recipientBareJid bare own JID (the stanza {@code to})
     * @param message PDO protocol message to encrypt
     * @param additionalNodes extra stanza children (the PDO {@code <meta>} node)
     * @return stanza plus the device-0 ciphertext type
     */
    public static Result buildPeerStanza(JaWaProtocolStore store,
                                         AuthCreds creds,
                                         String msgId,
                                         String recipientBareJid,
                                         Wa.Message message,
                                         List<BinaryNode> additionalNodes) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(creds);
        Objects.requireNonNull(message);

        Jid myJid = Jid.parse(recipientBareJid.endsWith("@lid") && creds.meLid != null && !creds.meLid.isBlank() ? creds.meLid : creds.meJid);
        if (myJid == null) throw new IllegalStateException("creds.meJid invalid");
        if (myJid.device() == 0) {
            throw new IllegalStateException(
                "peer stanza requires a companion device: creds.meJid has device 0, so device 0 is the sender itself");
        }
        Jid device0 = new Jid(myJid.user(), myJid.server(), 0, 0, myJid.domainType());
        SignalProtocolAddress addr = SessionBootstrap.addressFor(device0);

        byte[] dsmPadded = MessageEncoder.encode(
            MessageEncoder.deviceSent(resolveDsmDestination(recipientBareJid, creds), message));
        boolean includeDeviceIdentity = false;
        Map<SignalProtocolAddress, Integer> typeMap = new HashMap<>();
        List<BinaryNode> participants = new ArrayList<>();
        try {
            SessionCipher cipher = new SessionCipher(store, addr);
            CiphertextMessage ct = cipher.encrypt(dsmPadded);
            String type = ct.getType() == CiphertextMessage.PREKEY_TYPE ? "pkmsg" : "msg";
            if ("pkmsg".equals(type)) includeDeviceIdentity = true;
            typeMap.put(addr, ct.getType());
            BinaryNode enc = new BinaryNode("enc",
                Map.of("v", "2", "type", type), ct.serialize());
            participants.add(new BinaryNode("to",
                Map.of("jid", device0.asString()), List.of(enc)));
        } catch (UntrustedIdentityException e) {
            LOG.warn("Encrypt failed for peer device 0 {}: {}", device0.asString(), e.toString());
        }
        if (participants.isEmpty()) {
            throw new IllegalStateException("No recipients could be encrypted for; "
                + "did you bootstrap sessions first?");
        }
        List<BinaryNode> outer = new ArrayList<>();
        outer.add(new BinaryNode("participants", Map.of(), participants));
        if (includeDeviceIdentity && creds.account != null) {
            outer.add(new BinaryNode("device-identity", Map.of(),
                encodeDeviceIdentityForSend(creds.account)));
        }
        BinaryNode biz = BizNode.buildIfNeeded(message);
        if (biz != null) outer.add(biz);
        if (additionalNodes != null) outer.addAll(additionalNodes);
        Map<String, String> attrs = new HashMap<>();
        attrs.put("to", recipientBareJid);
        attrs.put("id", msgId);
        attrs.put("type", "text");
        return new Result(new BinaryNode("message", attrs, outer), typeMap);
    }

    /**
     * Increment 7 (stretch, builder only): reframes an already-built
     * DM stanza into Baileys' peer framing
     * ({@code lib/Socket/messages-send.js:761}): when
     * {@code additionalAttributes.category === 'peer'}, Baileys does
     * not wrap participant nodes in a {@code <participants>} element
     * — it pushes only the first participant's {@code <enc>} child
     * bare onto the stanza ({@code participants[0]?.content?.[0]}),
     * followed by the {@code <device-identity>} child when one was
     * included (pkmsg case) and then the additional nodes. This
     * method performs exactly that reframing on a stanza built by
     * {@link #buildStanza} / {@link #buildDualIdentityStanza}:
     * stanza attributes are preserved verbatim (callers still apply
     * the peer {@code category}/{@code push_priority} attributes via
     * {@link #withAdditionalAttributes}, as the PDO path does), the
     * {@code <participants>} wrapper is dropped, its first
     * {@code <to>} child's {@code <enc>} becomes a bare child, a
     * {@code <device-identity>} child (if present) follows it, and
     * every other original child (e.g. the PDO {@code <meta>} node)
     * follows in its original order.
     *
     * <p>Increment 8 wires this into the live PDO send: in
     * {@code JaWaClient.sendPeerDataOperationMessage}, the peer case
     * bypasses {@code queryDevices} and encrypts only for the single
     * own device-0 JID ({@link #buildPeerStanza}), so "first
     * participant" here is guaranteed to be the device-0 encryption
     * — reframing an enumerated DM stanza would not have that
     * guarantee, which is why the DM path is never reframed. The
     * device-0-is-the-sender fallback (bare {@code creds.meJid})
     * keeps the Increment 6 self-DM framing (documented in
     * {@link PeerDataOperations}); this builder changes no existing
     * send path.
     *
     * @param result built stanza to reframe; must not be null and
     *                 must carry a {@code <participants>} child whose
     *                 first {@code <to>} child carries an
     *                 {@code <enc>} child
     * @return a new message stanza with the peer framing
     * @throws IllegalArgumentException when the stanza lacks a
     *         participants/enc child to reframe
     */
    public static BinaryNode peerFrame(Result result) {
        if (result == null || result.stanza() == null) {
            throw new IllegalArgumentException("result stanza must not be null");
        }
        BinaryNode stanza = result.stanza();
        BinaryNode participants = stanza.child("participants");
        if (participants == null) {
            throw new IllegalArgumentException("stanza has no <participants> child to reframe");
        }
        BinaryNode firstTo = null;
        for (BinaryNode to : participants.childrenList()) {
            if ("to".equals(to.tag())) {
                firstTo = to;
                break;
            }
        }
        BinaryNode enc = firstTo == null ? null : firstTo.child("enc");
        if (enc == null) {
            throw new IllegalArgumentException("first participant carries no <enc> child to reframe");
        }
        List<BinaryNode> reframed = new ArrayList<>();
        reframed.add(enc);
        BinaryNode deviceIdentity = stanza.child("device-identity");
        if (deviceIdentity != null) reframed.add(deviceIdentity);
        for (BinaryNode child : stanza.childrenList()) {
            if ("participants".equals(child.tag()) || "device-identity".equals(child.tag())) continue;
            reframed.add(child);
        }
        return new BinaryNode(stanza.tag(), stanza.attrs(), reframed);
    }

    public static BinaryNode withAdditionalAttributes(BinaryNode stanza,
            Map<String, String> additionalAttributes) {
        if (stanza == null || additionalAttributes == null || additionalAttributes.isEmpty()) {
            return stanza;
        }
        Map<String, String> attrs = new HashMap<>(stanza.attrs());
        attrs.putAll(additionalAttributes);
        return new BinaryNode(stanza.tag(), attrs, stanza.content());
    }

    static byte[] encodeDeviceIdentityForSend(byte[] fullAccountBytes) {
        try {
            Wa.ADVSignedDeviceIdentity full = Wa.ADVSignedDeviceIdentity.parseFrom(fullAccountBytes);
            Wa.ADVSignedDeviceIdentity.Builder b = Wa.ADVSignedDeviceIdentity.newBuilder()
                .setDetails(full.getDetails())
                .setAccountSignature(full.getAccountSignature());
            if (full.hasDeviceSignature()) b.setDeviceSignature(full.getDeviceSignature());
            if (full.hasAccountSignatureKey()) b.setAccountSignatureKey(full.getAccountSignatureKey());

            return b.build().toByteArray();
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("invalid creds.account proto", e);
        }
    }

    public record Result(BinaryNode stanza, Map<SignalProtocolAddress, Integer> ciphertextTypes) {}
}
