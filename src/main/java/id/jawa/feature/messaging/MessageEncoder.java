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

import id.jawa.proto.Wa;
import id.jawa.protocol.crypto.Bytes;

public final class MessageEncoder {

    private MessageEncoder() {}

    public static byte[] encode(Wa.Message msg) {
        byte[] body = msg.toByteArray();
        return pad(body);
    }

    public static byte[] unpad(byte[] padded) {
        if (padded == null || padded.length == 0) {
            throw new IllegalArgumentException("empty payload");
        }
        int n = padded[padded.length - 1] & 0xFF;
        if (n == 0 || n > padded.length || n > 16) {
            throw new IllegalArgumentException("invalid pad length: " + n);
        }
        return Bytes.slice(padded, 0, padded.length - n);
    }

    public static byte[] pad(byte[] body) {
        int n = (Bytes.random(1)[0] & 0x0F) + 1;
        byte[] out = new byte[body.length + n];
        System.arraycopy(body, 0, out, 0, body.length);
        byte pad = (byte) n;
        for (int i = body.length; i < out.length; i++) out[i] = pad;
        return out;
    }

    public static Wa.Message text(String text) {
        return Wa.Message.newBuilder().setConversation(text).build();
    }

    public static Wa.Message statusTextMessage(String text, int backgroundArgb, int fontType) {
        var ext = Wa.Message.ExtendedTextMessage.newBuilder()
            .setText(text)
            .setBackgroundArgb(backgroundArgb);
        var font = Wa.Message.ExtendedTextMessage.FontType.forNumber(fontType);
        if (font != null) {
            ext.setFont(font);
        }
        return Wa.Message.newBuilder().setExtendedTextMessage(ext).build();
    }

    public static Wa.Message locationMessage(double latitude, double longitude,
                                             String name, String address) {
        Wa.Message.LocationMessage.Builder b = Wa.Message.LocationMessage.newBuilder()
            .setDegreesLatitude(latitude)
            .setDegreesLongitude(longitude);
        if (name != null && !name.isEmpty())       b.setName(name);
        if (address != null && !address.isEmpty()) b.setAddress(address);
        return Wa.Message.newBuilder().setLocationMessage(b.build()).build();
    }

    public static Wa.Message contactMessage(String displayName, String vcard) {
        Wa.Message.ContactMessage contact = Wa.Message.ContactMessage.newBuilder()
            .setDisplayName(displayName == null ? "" : displayName)
            .setVcard(vcard)
            .build();
        return Wa.Message.newBuilder().setContactMessage(contact).build();
    }

    public static Wa.Message viewOnceWrap(Wa.Message inner) {
        return Wa.Message.newBuilder()
            .setViewOnceMessageV2(Wa.Message.FutureProofMessage.newBuilder()
                .setMessage(inner).build())
            .build();
    }

    public static Wa.Message viewOnceExtensionWrap(Wa.Message inner) {
        return Wa.Message.newBuilder()
            .setViewOnceMessageV2Extension(Wa.Message.FutureProofMessage.newBuilder()
                .setMessage(inner).build())
            .build();
    }

    public static Wa.Message pollMessage(String name,
                                         java.util.List<String> options,
                                         int selectableCount) {
        Wa.Message.PollCreationMessage.Builder pcm = Wa.Message.PollCreationMessage.newBuilder()
            .setName(name)
            .setSelectableOptionsCount(selectableCount);
        for (String opt : options) {
            pcm.addOptions(Wa.Message.PollCreationMessage.Option.newBuilder()
                .setOptionName(opt).build());
        }
        Wa.MessageContextInfo mci = Wa.MessageContextInfo.newBuilder()
            .setMessageSecret(com.google.protobuf.ByteString.copyFrom(id.jawa.protocol.crypto.Bytes.random(32)))
            .build();
        Wa.Message.Builder mb = Wa.Message.newBuilder().setMessageContextInfo(mci);
        if (selectableCount == 1) {
            mb.setPollCreationMessageV3(pcm.build());
        } else {
            mb.setPollCreationMessage(pcm.build());
        }
        return mb.build();
    }

    public static Wa.Message textWithMentions(String body, java.util.List<String> mentionedBareJids) {
        Wa.ContextInfo.Builder ctx = Wa.ContextInfo.newBuilder();
        for (String jid : mentionedBareJids) ctx.addMentionedJid(jid);
        Wa.Message.ExtendedTextMessage extended = Wa.Message.ExtendedTextMessage.newBuilder()
            .setText(body)
            .setContextInfo(ctx.build())
            .build();
        return Wa.Message.newBuilder().setExtendedTextMessage(extended).build();
    }

    private static com.google.protobuf.ByteString bs(byte[] b) {
        return com.google.protobuf.ByteString.copyFrom(b);
    }

    public record ListRow(String rowId, String title, String description) {}

    public record ListSection(String title, java.util.List<ListRow> rows) {}

    public static Wa.Message listMessage(String title,
                                         String body,
                                         String footer,
                                         String buttonText,
                                         java.util.List<ListSection> sections) {
        Wa.Message.ListMessage.Builder b = Wa.Message.ListMessage.newBuilder()
            .setTitle(title)
            .setDescription(body)
            .setButtonText(buttonText)
            .setListType(Wa.Message.ListMessage.ListType.SINGLE_SELECT);
        if (footer != null && !footer.isEmpty()) b.setFooterText(footer);
        for (ListSection s : sections) {
            Wa.Message.ListMessage.Section.Builder sb = Wa.Message.ListMessage.Section.newBuilder();
            if (s.title() != null) sb.setTitle(s.title());
            for (ListRow r : s.rows()) {
                Wa.Message.ListMessage.Row.Builder rb = Wa.Message.ListMessage.Row.newBuilder();
                if (r.rowId() != null) rb.setRowId(r.rowId());
                if (r.title() != null) rb.setTitle(r.title());
                if (r.description() != null) rb.setDescription(r.description());
                sb.addRows(rb.build());
            }
            b.addSections(sb.build());
        }
        return Wa.Message.newBuilder().setListMessage(b.build()).build();
    }

    public record CtaButton(String name, String paramsJson) {

        public static CtaButton url(String displayText, String url) {
            return new CtaButton("cta_url",
                "{\"display_text\":\"" + escape(displayText)
              + "\",\"url\":\"" + escape(url)
              + "\",\"merchant_url\":\"" + escape(url) + "\"}");
        }

        public static CtaButton copy(String displayText, String copyCode) {
            return new CtaButton("cta_copy",
                "{\"display_text\":\"" + escape(displayText)
              + "\",\"copy_code\":\"" + escape(copyCode) + "\"}");
        }

        public static CtaButton call(String displayText, String phoneNumber) {
            return new CtaButton("cta_call",
                "{\"display_text\":\"" + escape(displayText)
              + "\",\"phone_number\":\"" + escape(phoneNumber) + "\"}");
        }

        public static CtaButton quickReply(String displayText, String id) {
            return new CtaButton("quick_reply",
                "{\"display_text\":\"" + escape(displayText)
              + "\",\"id\":\"" + escape(id) + "\"}");
        }

        public static CtaButton singleSelect(String displayText, java.util.List<ListSection> sections) {
            StringBuilder b = new StringBuilder("{\"title\":\"").append(escape(displayText));
            b.append("\",\"sections\":[");
            for (int i = 0; i < sections.size(); i++) {
                if (i > 0) b.append(",");
                ListSection s = sections.get(i);
                b.append("{\"title\":\"").append(escape(s.title() == null ? "" : s.title()));
                b.append("\",\"rows\":[");
                for (int j = 0; j < s.rows().size(); j++) {
                    if (j > 0) b.append(",");
                    ListRow r = s.rows().get(j);
                    b.append("{\"id\":\"").append(escape(r.rowId() == null ? "" : r.rowId()));
                    b.append("\",\"title\":\"").append(escape(r.title() == null ? "" : r.title()));
                    b.append("\",\"description\":\"").append(escape(r.description() == null ? "" : r.description()));
                    b.append("\"}");
                }
                b.append("]}");
            }
            b.append("]}");
            return new CtaButton("single_select", b.toString());
        }
    }

    public record CarouselCard(String title, String caption,
                               Wa.Message.ImageMessage imageHeader,
                               Wa.Message.VideoMessage videoHeader,
                               String footer,
                               java.util.List<CtaButton> buttons) {}

    public static Wa.Message interactiveCarousel(String body, String footer,
                                                 java.util.List<CarouselCard> cards) {
        Wa.Message.InteractiveMessage.CarouselMessage.Builder cm =
            Wa.Message.InteractiveMessage.CarouselMessage.newBuilder()
                .setMessageVersion(1);
        for (CarouselCard card : cards) {
            Wa.Message.InteractiveMessage.NativeFlowMessage.Builder nf =
                Wa.Message.InteractiveMessage.NativeFlowMessage.newBuilder()
                    .setMessageVersion(1);
            for (CtaButton btn : card.buttons()) {
                nf.addButtons(Wa.Message.InteractiveMessage.NativeFlowMessage.NativeFlowButton.newBuilder()
                    .setName(btn.name())
                    .setButtonParamsJson(btn.paramsJson())
                    .build());
            }
            Wa.Message.InteractiveMessage.Header.Builder hb = Wa.Message.InteractiveMessage.Header.newBuilder()
                .setTitle(card.title() == null ? "" : card.title())
                .setHasMediaAttachment(true);
            if (card.imageHeader() != null)       hb.setImageMessage(card.imageHeader());
            else if (card.videoHeader() != null)  hb.setVideoMessage(card.videoHeader());
            else throw new IllegalArgumentException("CarouselCard requires imageHeader or videoHeader");

            Wa.Message.InteractiveMessage.Builder cardMsg = Wa.Message.InteractiveMessage.newBuilder()
                .setHeader(hb.build())
                .setBody(Wa.Message.InteractiveMessage.Body.newBuilder()
                    .setText(card.caption() == null ? "" : card.caption())
                    .build())
                .setNativeFlowMessage(nf.build());
            if (card.footer() != null && !card.footer().isEmpty()) {
                cardMsg.setFooter(Wa.Message.InteractiveMessage.Footer.newBuilder()
                    .setText(card.footer()).build());
            }
            cm.addCards(cardMsg.build());
        }
        Wa.Message.InteractiveMessage.Builder im = Wa.Message.InteractiveMessage.newBuilder()
            .setCarouselMessage(cm.build());
        if (body != null && !body.isEmpty()) {
            im.setBody(Wa.Message.InteractiveMessage.Body.newBuilder().setText(body).build());
        }
        if (footer != null && !footer.isEmpty()) {
            im.setFooter(Wa.Message.InteractiveMessage.Footer.newBuilder().setText(footer).build());
        }
        return Wa.Message.newBuilder().setInteractiveMessage(im.build()).build();
    }

    public static Wa.Message interactiveCtaButtons(String body, String footer,
                                                   java.util.List<CtaButton> buttons) {
        Wa.Message.InteractiveMessage.NativeFlowMessage.Builder nf =
            Wa.Message.InteractiveMessage.NativeFlowMessage.newBuilder()
                .setMessageVersion(1);
        for (CtaButton b : buttons) {
            nf.addButtons(Wa.Message.InteractiveMessage.NativeFlowMessage.NativeFlowButton.newBuilder()
                .setName(b.name())
                .setButtonParamsJson(b.paramsJson())
                .build());
        }
        Wa.Message.InteractiveMessage.Builder im = Wa.Message.InteractiveMessage.newBuilder()
            .setBody(Wa.Message.InteractiveMessage.Body.newBuilder().setText(body).build())
            .setNativeFlowMessage(nf.build());
        if (footer != null && !footer.isEmpty()) {
            im.setFooter(Wa.Message.InteractiveMessage.Footer.newBuilder().setText(footer).build());
        }
        return Wa.Message.newBuilder().setInteractiveMessage(im.build()).build();
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default   -> b.append(c);
            }
        }
        return b.toString();
    }

    public record QuickReplyButton(String buttonId, String displayText) {}

    public static Wa.Message buttonsMessage(String body, String footer,
                                            java.util.List<QuickReplyButton> buttons) {
        Wa.Message.ButtonsMessage.Builder b = Wa.Message.ButtonsMessage.newBuilder()
            .setContentText(body)
            .setHeaderType(Wa.Message.ButtonsMessage.HeaderType.EMPTY);
        if (footer != null && !footer.isEmpty()) b.setFooterText(footer);
        for (QuickReplyButton qb : buttons) {
            Wa.Message.ButtonsMessage.Button button = Wa.Message.ButtonsMessage.Button.newBuilder()
                .setButtonId(qb.buttonId())
                .setButtonText(Wa.Message.ButtonsMessage.Button.ButtonText.newBuilder()
                    .setDisplayText(qb.displayText())
                    .build())
                .setType(Wa.Message.ButtonsMessage.Button.Type.RESPONSE)
                .build();
            b.addButtons(button);
        }
        return Wa.Message.newBuilder().setButtonsMessage(b.build()).build();
    }

    public static Wa.Message videoMessage(
            String uploadedUrl, String directPath,
            byte[] mediaKey, byte[] fileSha256, byte[] fileEncSha256,
            long fileLength, String mimetype,
            String caption, int seconds, int width, int height) {
        Wa.Message.VideoMessage.Builder b = Wa.Message.VideoMessage.newBuilder()
            .setUrl(uploadedUrl)
            .setDirectPath(directPath)
            .setMediaKey(bs(mediaKey))
            .setFileSha256(bs(fileSha256))
            .setFileEncSha256(bs(fileEncSha256))
            .setFileLength(fileLength)
            .setMimetype(mimetype)
            .setMediaKeyTimestamp(System.currentTimeMillis() / 1000);
        if (caption != null && !caption.isEmpty()) b.setCaption(caption);
        if (seconds > 0) b.setSeconds(seconds);
        if (width > 0)   b.setWidth(width);
        if (height > 0)  b.setHeight(height);
        return Wa.Message.newBuilder().setVideoMessage(b.build()).build();
    }

    public static Wa.Message audioMessage(
            String uploadedUrl, String directPath,
            byte[] mediaKey, byte[] fileSha256, byte[] fileEncSha256,
            long fileLength, String mimetype,
            int seconds, boolean ptt) {
        Wa.Message.AudioMessage.Builder b = Wa.Message.AudioMessage.newBuilder()
            .setUrl(uploadedUrl)
            .setDirectPath(directPath)
            .setMediaKey(bs(mediaKey))
            .setFileSha256(bs(fileSha256))
            .setFileEncSha256(bs(fileEncSha256))
            .setFileLength(fileLength)
            .setMimetype(mimetype)
            .setPtt(ptt)
            .setMediaKeyTimestamp(System.currentTimeMillis() / 1000);
        if (seconds > 0) b.setSeconds(seconds);
        return Wa.Message.newBuilder().setAudioMessage(b.build()).build();
    }

    public static Wa.Message documentMessage(
            String uploadedUrl, String directPath,
            byte[] mediaKey, byte[] fileSha256, byte[] fileEncSha256,
            long fileLength, String mimetype,
            String fileName, String title) {
        Wa.Message.DocumentMessage.Builder b = Wa.Message.DocumentMessage.newBuilder()
            .setUrl(uploadedUrl)
            .setDirectPath(directPath)
            .setMediaKey(bs(mediaKey))
            .setFileSha256(bs(fileSha256))
            .setFileEncSha256(bs(fileEncSha256))
            .setFileLength(fileLength)
            .setMimetype(mimetype)
            .setMediaKeyTimestamp(System.currentTimeMillis() / 1000);
        if (fileName != null && !fileName.isEmpty()) b.setFileName(fileName);
        if (title != null && !title.isEmpty())       b.setTitle(title);
        return Wa.Message.newBuilder().setDocumentMessage(b.build()).build();
    }

    public static Wa.Message imageMessage(
            String uploadedUrl,
            String directPath,
            byte[] mediaKey,
            byte[] fileSha256,
            byte[] fileEncSha256,
            long fileLength,
            String mimetype,
            String caption) {
        Wa.Message.ImageMessage.Builder b = Wa.Message.ImageMessage.newBuilder()
            .setUrl(uploadedUrl)
            .setDirectPath(directPath)
            .setMediaKey(com.google.protobuf.ByteString.copyFrom(mediaKey))
            .setFileSha256(com.google.protobuf.ByteString.copyFrom(fileSha256))
            .setFileEncSha256(com.google.protobuf.ByteString.copyFrom(fileEncSha256))
            .setFileLength(fileLength)
            .setMimetype(mimetype)
            .setMediaKeyTimestamp(System.currentTimeMillis() / 1000);
        if (caption != null && !caption.isEmpty()) b.setCaption(caption);
        return Wa.Message.newBuilder().setImageMessage(b.build()).build();
    }

    public static Wa.Message reply(String text,
                                   String quotedMsgId,
                                   String quotedSender,
                                   String quotedText) {
        Wa.Message quotedStub = Wa.Message.newBuilder()
            .setConversation(quotedText == null ? "" : quotedText)
            .build();
        Wa.ContextInfo.Builder ctx = Wa.ContextInfo.newBuilder()
            .setStanzaId(quotedMsgId)
            .setQuotedMessage(quotedStub);
        if (quotedSender != null && !quotedSender.isBlank()) {
            ctx.setParticipant(quotedSender);
        }
        Wa.Message.ExtendedTextMessage extended = Wa.Message.ExtendedTextMessage.newBuilder()
            .setText(text)
            .setContextInfo(ctx.build())
            .build();
        return Wa.Message.newBuilder().setExtendedTextMessage(extended).build();
    }

    public static Wa.Message quote(Wa.Message message, String quotedMsgId, String quotedSender, String quotedText) {
        if (message == null) return null;
        Wa.Message quotedStub = Wa.Message.newBuilder()
            .setConversation(quotedText == null ? "" : quotedText)
            .build();
        Wa.ContextInfo.Builder ctx = Wa.ContextInfo.newBuilder()
            .setStanzaId(quotedMsgId)
            .setQuotedMessage(quotedStub);
        if (quotedSender != null && !quotedSender.isBlank()) {
            ctx.setParticipant(quotedSender);
        }
        Wa.Message.Builder builder = message.toBuilder();
        if (message.hasInteractiveMessage()) {
            builder.setInteractiveMessage(message.getInteractiveMessage().toBuilder().setContextInfo(ctx.build()));
        } else if (message.hasExtendedTextMessage()) {
            builder.setExtendedTextMessage(message.getExtendedTextMessage().toBuilder().setContextInfo(ctx.build()));
        } else if (message.hasConversation()) {
            builder.setExtendedTextMessage(Wa.Message.ExtendedTextMessage.newBuilder()
                .setText(message.getConversation())
                .setContextInfo(ctx.build())
                .build())
                .clearConversation();
        }
        return builder.build();
    }

    public static Wa.Message edit(String chatJid,
                                  String targetMsgId,
                                  Wa.Message newContent,
                                  long timestampMs) {
        Wa.MessageKey key = Wa.MessageKey.newBuilder()
            .setFromMe(true)
            .setId(targetMsgId)
            .setRemoteJid(chatJid)
            .build();
        Wa.Message.ProtocolMessage protocol = Wa.Message.ProtocolMessage.newBuilder()
            .setKey(key)
            .setType(Wa.Message.ProtocolMessage.Type.MESSAGE_EDIT)
            .setEditedMessage(newContent)
            .setTimestampMs(timestampMs)
            .build();
        Wa.Message inner = Wa.Message.newBuilder().setProtocolMessage(protocol).build();
        Wa.Message.FutureProofMessage wrap = Wa.Message.FutureProofMessage.newBuilder()
            .setMessage(inner)
            .build();
        return Wa.Message.newBuilder().setEditedMessage(wrap).build();
    }

    public static Wa.Message revoke(String chatJid,
                                    String targetMsgId,
                                    String targetParticipant,
                                    boolean fromMe) {
        Wa.MessageKey.Builder key = Wa.MessageKey.newBuilder()
            .setRemoteJid(chatJid)
            .setFromMe(fromMe)
            .setId(targetMsgId);
        if (targetParticipant != null && !targetParticipant.isBlank()) {
            key.setParticipant(targetParticipant);
        }
        Wa.Message.ProtocolMessage protocol = Wa.Message.ProtocolMessage.newBuilder()
            .setKey(key.build())
            .setType(Wa.Message.ProtocolMessage.Type.REVOKE)
            .build();
        return Wa.Message.newBuilder().setProtocolMessage(protocol).build();
    }

    public static Wa.Message reaction(String chatJid,
                                      String targetMsgId,
                                      String targetParticipant,
                                      boolean fromMe,
                                      String emoji,
                                      long timestampMs) {
        Wa.MessageKey.Builder key = Wa.MessageKey.newBuilder()
            .setRemoteJid(chatJid)
            .setFromMe(fromMe)
            .setId(targetMsgId);
        if (targetParticipant != null) key.setParticipant(targetParticipant);

        Wa.Message.ReactionMessage reaction = Wa.Message.ReactionMessage.newBuilder()
            .setKey(key.build())
            .setText(emoji == null ? "" : emoji)
            .setSenderTimestampMs(timestampMs)
            .build();
        return Wa.Message.newBuilder().setReactionMessage(reaction).build();
    }

    public static Wa.Message deviceSent(String destinationJid, Wa.Message inner) {
        return Wa.Message.newBuilder()
            .setDeviceSentMessage(Wa.Message.DeviceSentMessage.newBuilder()
                .setDestinationJid(destinationJid)
                .setMessage(inner)
                .build())
            .build();
    }
}
