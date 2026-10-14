package id.jawa.feature.messaging;

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

import com.google.protobuf.InvalidProtocolBufferException;
import id.jawa.protocol.codec.BinaryNode;
import id.jawa.proto.Wa;
import id.jawa.feature.signal.JaWaProtocolStore;
import id.jawa.feature.signal.SessionBootstrap;
import id.jawa.domain.model.Jid;
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

public final class MessageReceiver {

    private static final Logger LOG = LoggerFactory.getLogger(MessageReceiver.class);

    private MessageReceiver() {}

    public record Decoded(String senderJid, String groupJid, String msgId, String encType,
                          Wa.Message message, String text, InteractiveResponse interactive,
                          long timestamp) {
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
            id.jawa.domain.model.Jid fromJid = id.jawa.domain.model.Jid.parse(from);
            id.jawa.domain.model.Jid pnJid = id.jawa.domain.model.Jid.parse(senderPn);
            if (fromJid != null && pnJid != null) {
                String fromBare = fromJid.user() + "@lid";
                String pnBare = pnJid.user() + "@" + id.jawa.domain.model.Jid.SERVER_WHATSAPP;
                id.jawa.client.JaWaClient.LID_TO_PN_MAP.put(fromBare, pnBare);
                LOG.debug("LID mapping added from sender_pn: {} -> {}", fromBare, pnBare);
            }
        }
        String participantPn = messageStanza.attr("participant_pn");
        if (participantPn != null && !participantPn.isBlank() && participant != null && participant.endsWith("@lid")) {
            id.jawa.domain.model.Jid partJid = id.jawa.domain.model.Jid.parse(participant);
            id.jawa.domain.model.Jid pnJid = id.jawa.domain.model.Jid.parse(participantPn);
            if (partJid != null && pnJid != null) {
                String partBare = partJid.user() + "@lid";
                String pnBare = pnJid.user() + "@" + id.jawa.domain.model.Jid.SERVER_WHATSAPP;
                id.jawa.client.JaWaClient.LID_TO_PN_MAP.put(partBare, pnBare);
                LOG.debug("LID mapping added from participant_pn: {} -> {}", partBare, pnBare);
            }
        }

        BinaryNode enc = findEnc(messageStanza);
        if (enc == null) {
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
            throw new DecryptException("decrypt failed for " + senderAddr + ": " + e, e);
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
                var matcher = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(params);
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

    public static final class DecryptException extends Exception {
        public DecryptException(String message) { super(message); }
        public DecryptException(String message, Throwable cause) { super(message, cause); }
    }
}
