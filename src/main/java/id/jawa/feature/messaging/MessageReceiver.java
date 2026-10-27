package id.jawa.feature.messaging;


import com.google.protobuf.InvalidProtocolBufferException;
import id.jawa.protocol.codec.BinaryNode;
import id.jawa.proto.Wa;
import id.jawa.client.JaWaClient;
import id.jawa.domain.model.Jid;
import id.jawa.feature.signal.JaWaProtocolStore;
import id.jawa.feature.signal.SessionBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.UntrustedIdentityException;
import org.whispersystems.libsignal.groups.GroupCipher;
import org.whispersystems.libsignal.groups.GroupSessionBuilder;
import org.whispersystems.libsignal.groups.SenderKeyName;
import org.whispersystems.libsignal.groups.state.SenderKeyStore;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.protocol.SenderKeyDistributionMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;

import java.util.regex.Pattern;

/**
 * Decrypts and unpacks inbound Signal Protocol encrypted stanzas into structured {@link Decoded} messages.
 * <p>
 * Handles direct messages, sender-key group messages, pre-key sessions, and interactive callback responses.
 */
public final class MessageReceiver {

    private static final Logger LOG = LoggerFactory.getLogger(MessageReceiver.class);

    private MessageReceiver() {}

    /**
     * Increment 9: trailing stub components, following the Increment 6
     * {@code MessagesUpdate.groupInviteMessage} / Increment 8
     * {@code MessageUpsert.requestId} pattern: Baileys'
     * {@code WebMessageInfo.messageStubType} /
     * {@code messageStubParameters} ({@code lib/Types/Message.js}:
     * {@code WAMessageStubType = proto.WebMessageInfo.StubType}).
     * They are null for every pre-existing construction site (the
     * delegating constructors below default them to null), so normal
     * inbound messages still carry null stub fields; only the
     * Increment 9 community-V4 synthetic upsert populates them
     * (see {@code JaWaClient.acceptCommunityInviteV4}). Baileys
     * {@code messageStubParameters} is a string array, modelled here
     * as a nullable list, copied on construction when non-null.
     * <p>
     * Deliberately not modelled here (pre-existing documented loss,
     * unchanged): {@code fromMe} / key {@code participant} are not
     * Decoded fields, so the V4 synthetic's Baileys
     * {@code participant: key.remoteJid} is carried only via
     * {@code senderJid}, as before.
     */
    public record Decoded(String senderJid, String groupJid, String msgId, String encType,
                          Wa.Message message, String text, InteractiveResponse interactive,
                          long timestamp, Wa.WebMessageInfo.StubType messageStubType,
                          java.util.List<String> messageStubParameters) {
        public Decoded {
            messageStubParameters = messageStubParameters == null ? null
                : java.util.List.copyOf(messageStubParameters);
        }

        public Decoded(String senderJid, String groupJid, String msgId, String encType,
                       Wa.Message message, String text, InteractiveResponse interactive,
                       long timestamp) {
            this(senderJid, groupJid, msgId, encType, message, text, interactive, timestamp,
                null, null);
        }

        public Decoded(String senderJid, String groupJid, String msgId, String encType,
                       Wa.Message message, String text, InteractiveResponse interactive) {
            this(senderJid, groupJid, msgId, encType, message, text, interactive, 0L);
        }
    }

    public record InteractiveResponse(String kind, String selectedId,
                                      String paramsJson, String displayText) {}

    public static Decoded decode(BinaryNode messageStanza,
                                 JaWaProtocolStore signalStore,
                                 SenderKeyStore senderKeyStore)
            throws DecryptException {
        String msgId = messageStanza.attr("id");
        String from = messageStanza.attr("from");
        String participant = messageStanza.attr("participant");
        long timestamp = 0L;
        String tAttr = messageStanza.attr("t");
        if (tAttr != null && !tAttr.isEmpty()) {
            try {
                timestamp = Long.parseLong(tAttr);
            } catch (NumberFormatException ignored) {}
        }
        String senderJidStr = participant != null ? participant : from;
        if (senderJidStr == null) {
            throw new DecryptException("message has no from/participant attr");
        }

        boolean isGroup = participant != null && from != null && !from.equals(participant);
        String groupJid = isGroup ? from : null;

        String senderPn = messageStanza.attr("sender_pn");
        if (senderPn != null && !senderPn.isBlank() && from != null && from.endsWith("@lid")) {
            Jid fromJid = Jid.parse(from);
            Jid pnJid = Jid.parse(senderPn);
            if (fromJid != null && pnJid != null) {
                String fromBare = fromJid.user() + "@lid";
                String pnBare = pnJid.user() + "@" + Jid.SERVER_WHATSAPP;
                JaWaClient.LID_TO_PN_MAP.put(fromBare, pnBare);
                LOG.debug("LID mapping added from sender_pn: {} -> {}", fromBare, pnBare);
            }
        }
        String participantPn = messageStanza.attr("participant_pn");
        if (participantPn != null && !participantPn.isBlank() && participant != null && participant.endsWith("@lid")) {
            Jid partJid = Jid.parse(participant);
            Jid pnJid = Jid.parse(participantPn);
            if (partJid != null && pnJid != null) {
                String partBare = partJid.user() + "@lid";
                String pnBare = pnJid.user() + "@" + Jid.SERVER_WHATSAPP;
                JaWaClient.LID_TO_PN_MAP.put(partBare, pnBare);
                LOG.debug("LID mapping added from participant_pn: {} -> {}", partBare, pnBare);
            }
        }

        BinaryNode enc = findEnc(messageStanza);
        if (enc == null) {
            // Increment 10: Baileys' no-decryptable-content shape
            // (verified, not guessed). In
            // {@code lib/Utils/decode-wa-message.js} decrypt() counts a
            // stanza child as decryptable only when its tag is
            // {@code enc} (or {@code plaintext}) with byte content;
            // when the count is 0 and the message is not a view-once
            // placeholder, it sets stub CIPHERTEXT /
            // [NO_MESSAGE_FOUND_ERROR_TEXT]. wa-java's BinaryNode
            // equivalent of "carries an <unavailable> node" is a direct
            // <unavailable> child (Baileys reads it with
            // getBinaryNodeChild(node, 'unavailable') in
            // messages-recv.js:1309, and decode-wa-message.js marks
            // <unavailable type='view_once'> as isViewOnce, which
            // suppresses the stub). So: an <unavailable> child that is
            // NOT type view_once, with no <enc>, throws
            // NO_MESSAGE_FOUND. Two deliberate preservations: (a) a
            // view_once <unavailable> with no <enc> still returns the
            // null-message Decoded, as Baileys suppresses its stub;
            // (b) a plain no-<enc> stanza with NO <unavailable> child
            // still returns the null-message Decoded exactly as before
            // — wa-java's existing path for unencrypted/plain
            // messages is not reclassified here (Baileys would stub
            // it too, but reclassifying it would change the normal
            // inbound path for every unencrypted message; that wider
            // change is left to the coordinator, see the report).
            BinaryNode unavailable = messageStanza.child("unavailable");
            if (unavailable != null && !"view_once".equals(unavailable.attr("type"))) {
                throw new DecryptException(NO_MESSAGE_FOUND_ERROR_TEXT,
                    DecryptFailureKind.NO_MESSAGE_FOUND);
            }
            return new Decoded(senderJidStr, groupJid, msgId, null, null, null, null, timestamp);
        }

        String encType = enc.attr("type");
        byte[] ciphertext = enc.bytesContent();
        if (ciphertext == null || ciphertext.length == 0) {
            throw new DecryptException("empty enc payload (type=" + encType + ")");
        }

        Jid senderJid = Jid.parse(senderJidStr);
        if (senderJid == null) {
            throw new DecryptException("malformed sender JID: " + senderJidStr);
        }
        SignalProtocolAddress senderAddr = SessionBootstrap.addressFor(senderJid);

        byte[] padded;
        try {
            padded = switch (encType) {
                case "pkmsg" -> new SessionCipher(signalStore, senderAddr)
                    .decrypt(new PreKeySignalMessage(ciphertext));
                case "msg"   -> new SessionCipher(signalStore, senderAddr)
                    .decrypt(new SignalMessage(ciphertext));
                case "skmsg" -> {
                    if (groupJid == null) {
                        throw new DecryptException("skmsg without group JID");
                    }
                    SenderKeyName name = new SenderKeyName(groupJid, senderAddr);
                    yield new GroupCipher(senderKeyStore, name).decrypt(ciphertext);
                }
                default      -> throw new DecryptException("unsupported enc type: " + encType);
            };
        } catch (InvalidVersionException | InvalidMessageException | InvalidKeyException
                | InvalidKeyIdException | NoSessionException | UntrustedIdentityException
                | DuplicateMessageException | LegacyMessageException e) {
            // Increment 10: classify so a MISSING_KEYS libsignal
            // failure is distinguishable; all other libsignal failures
            // stay OTHER and therefore placeholder-ineligible.
            throw new DecryptException("decrypt failed for " + senderAddr + ": " + e, e,
                classifyFailure(e));
        }

        byte[] body = MessageEncoder.unpad(padded);
        Wa.Message message;
        try {
            message = Wa.Message.parseFrom(body);
        } catch (InvalidProtocolBufferException e) {
            throw new DecryptException("invalid Wa.Message proto from " + senderAddr, e);
        }

        if (message.hasSenderKeyDistributionMessage()) {
            processSkdm(message.getSenderKeyDistributionMessage(), senderAddr, senderKeyStore);
        }

        boolean unwrapped = true;
        while (unwrapped) {
            unwrapped = false;
            if (message.hasEphemeralMessage() && message.getEphemeralMessage().hasMessage()) {
                message = message.getEphemeralMessage().getMessage();
                unwrapped = true;
            } else if (message.hasViewOnceMessage() && message.getViewOnceMessage().hasMessage()) {
                message = message.getViewOnceMessage().getMessage();
                unwrapped = true;
            } else if (message.hasViewOnceMessageV2() && message.getViewOnceMessageV2().hasMessage()) {
                message = message.getViewOnceMessageV2().getMessage();
                unwrapped = true;
            } else if (message.hasDocumentWithCaptionMessage() && message.getDocumentWithCaptionMessage().hasMessage()) {
                message = message.getDocumentWithCaptionMessage().getMessage();
                unwrapped = true;
            } else if (message.hasDeviceSentMessage() && message.getDeviceSentMessage().hasMessage()) {
                message = message.getDeviceSentMessage().getMessage();
                unwrapped = true;
            }
        }

        String text = message.hasConversation() ? message.getConversation() : null;
        if (text == null && message.hasExtendedTextMessage()
                && message.getExtendedTextMessage().hasText()) {
            text = message.getExtendedTextMessage().getText();
        }

        InteractiveResponse interactive = parseInteractive(message);
        LOG.debug("Decoded {} from {}{} (encType={}, text={}, interactive={})",
            msgId, senderJidStr,
            groupJid != null ? " in group " + groupJid : "",
            encType, text != null ? "yes" : "no",
            interactive != null ? interactive.kind() + "/" + interactive.selectedId() : "no");
        return new Decoded(senderJidStr, groupJid, msgId, encType, message, text, interactive, timestamp);
    }

    private static InteractiveResponse parseInteractive(Wa.Message message) {
        if (message.hasButtonsResponseMessage()) {
            var b = message.getButtonsResponseMessage();
            return new InteractiveResponse("buttons",
                b.getSelectedButtonId(), null, b.getSelectedDisplayText());
        }
        if (message.hasListResponseMessage()) {
            var l = message.getListResponseMessage();
            String rowId = l.hasSingleSelectReply() ? l.getSingleSelectReply().getSelectedRowId() : "";
            return new InteractiveResponse("list", rowId, null, l.getTitle());
        }
        if (message.hasInteractiveResponseMessage()) {
            var ir = message.getInteractiveResponseMessage();
            String name = ir.hasNativeFlowResponseMessage() ? ir.getNativeFlowResponseMessage().getName() : "";
            String params = ir.hasNativeFlowResponseMessage() ? ir.getNativeFlowResponseMessage().getParamsJson() : null;
            String body = ir.hasBody() ? ir.getBody().getText() : null;

            String selectedId = null;
            if (params != null) {
                var matcher = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(params);
                if (matcher.find()) {
                    selectedId = matcher.group(1);
                }
            }
            if (selectedId == null) {
                selectedId = name;
            }

            return new InteractiveResponse("native_flow", selectedId, params, body);
        }
        if (message.hasTemplateButtonReplyMessage()) {
            var t = message.getTemplateButtonReplyMessage();
            return new InteractiveResponse("template",
                t.getSelectedId(), null, t.getSelectedDisplayText());
        }
        return null;
    }

    private static void processSkdm(Wa.Message.SenderKeyDistributionMessage proto,
                                    SignalProtocolAddress senderAddr,
                                    SenderKeyStore senderKeyStore) {
        String groupId = proto.getGroupId();
        byte[] axoBytes = proto.getAxolotlSenderKeyDistributionMessage().toByteArray();
        if (groupId.isEmpty() || axoBytes.length == 0) {
            LOG.warn("Skipping malformed SKDM (groupId='{}', axoBytes={}B)", groupId, axoBytes.length);
            return;
        }
        try {
            SenderKeyDistributionMessage skdm = new SenderKeyDistributionMessage(axoBytes);
            SenderKeyName name = new SenderKeyName(groupId, senderAddr);
            new GroupSessionBuilder(senderKeyStore).process(name, skdm);
            LOG.debug("Processed SKDM from {} for group {}", senderAddr, groupId);
        } catch (LegacyMessageException | InvalidMessageException e) {
            LOG.warn("Invalid SKDM from {} for group {}: {}", senderAddr, groupId, e.toString());
        }
    }

    private static BinaryNode findEnc(BinaryNode stanza) {
        for (BinaryNode child : stanza.childrenList()) {
            if (!"enc".equals(child.tag())) continue;
            String type = child.attr("type");
            if ("msg".equals(type) || "pkmsg".equals(type) || "skmsg".equals(type)) return child;
        }
        return null;
    }

    /**
     * Increment 10: structured decrypt-failure kind, so the receiver
     * can tell the cases Baileys distinguishes in
     * {@code lib/Socket/messages-recv.js:1301-1360} apart without
     * string-matching in the catch block. Only
     * {@link #NO_MESSAGE_FOUND} is placeholder-eligible (see
     * {@link PeerDataOperations#isPlaceholderResendEligible}); every
     * other kind maps to "not placeholder-eligible" by default, so
     * existing behaviour for NoSessionException / bad-MAC / etc. is
     * unchanged. {@link #MISSING_KEYS} is classified but deliberately
     * does NOT change the catch path: Baileys NACKs it
     * ({@code NACK_REASONS.ParsingError}) instead of retry-receipting,
     * and changing wa-java's receipt behaviour here is out of scope
     * for Increment 10 (see the task's "do not silently change
     * send-path" rule), so a MISSING_KEYS failure still goes through
     * the unchanged ack + retry-receipt catch path.
     */
    public enum DecryptFailureKind {
        NO_MESSAGE_FOUND,
        MISSING_KEYS,
        OTHER;

        /** Only NO_MESSAGE_FOUND may trigger a placeholder upsert/claim. */
        public boolean isPlaceholderEligible() { return this == NO_MESSAGE_FOUND; }
    }

    /**
     * Baileys {@code NO_MESSAGE_FOUND_ERROR_TEXT}
     * ({@code lib/Utils/decode-wa-message.js:28}):
     * {@code 'Message absent from node'} — the stub parameter Baileys
     * sets when a stanza carries nothing decryptable.
     */
    public static final String NO_MESSAGE_FOUND_ERROR_TEXT = "Message absent from node";
    /**
     * Baileys {@code MISSING_KEYS_ERROR_TEXT}
     * ({@code lib/Utils/decode-wa-message.js:29}):
     * {@code 'Key used already or never filled'}.
     */
    public static final String MISSING_KEYS_ERROR_TEXT = "Key used already or never filled";

    /**
     * Classifies a decrypt failure from its cause chain: a cause whose
     * message contains {@link #MISSING_KEYS_ERROR_TEXT} is
     * {@link DecryptFailureKind#MISSING_KEYS}, anything else is
     * {@link DecryptFailureKind#OTHER}. NO_MESSAGE_FOUND is never
     * produced here — it is produced only by the no-decryptable-content
     * branch of {@link #decode}, never by a failed decrypt attempt
     * (Baileys likewise sets it only when {@code decryptables == 0}).
     */
    public static DecryptFailureKind classifyFailure(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && m.contains(MISSING_KEYS_ERROR_TEXT)) {
                return DecryptFailureKind.MISSING_KEYS;
            }
        }
        return DecryptFailureKind.OTHER;
    }

    public static final class DecryptException extends Exception {
        private final DecryptFailureKind kind;

        public DecryptException(String message) { this(message, (Throwable) null); }
        public DecryptException(String message, Throwable cause) {
            this(message, cause, DecryptFailureKind.OTHER);
        }
        public DecryptException(String message, DecryptFailureKind kind) {
            this(message, null, kind);
        }
        public DecryptException(String message, Throwable cause, DecryptFailureKind kind) {
            super(message, cause);
            this.kind = kind == null ? DecryptFailureKind.OTHER : kind;
        }

        /** Structured failure kind; never null. Defaults to OTHER at legacy sites. */
        public DecryptFailureKind kind() { return kind; }

        /** True only for {@link DecryptFailureKind#NO_MESSAGE_FOUND}. */
        public boolean isPlaceholderEligible() { return kind.isPlaceholderEligible(); }
    }
}
