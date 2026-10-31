package id.ja_wa.feature.messaging;

import com.google.protobuf.ByteString;
import id.ja_wa.event.LidMappingUpdate;
import id.ja_wa.event.MessagingHistorySet;
import id.ja_wa.proto.Wa;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.Deflater;

import static org.assertj.core.api.Assertions.assertThat;

class HistorySyncNotificationParserTest {

    private static Wa.WebMessageInfo webMessage(String remoteJid, String participant,
            String id, boolean fromMe, String text, String receiptPn) {
        Wa.MessageKey.Builder key = Wa.MessageKey.newBuilder()
            .setRemoteJid(remoteJid).setFromMe(fromMe).setId(id);
        if (participant != null) {
            key.setParticipant(participant);
        }
        Wa.WebMessageInfo.Builder info = Wa.WebMessageInfo.newBuilder()
            .setKey(key)
            .setMessage(Wa.Message.newBuilder().setConversation(text))
            .setMessageTimestamp(1700000000L);
        if (receiptPn != null) {
            info.addUserReceipt(Wa.UserReceipt.newBuilder()
                .setUserJid(receiptPn));
        }
        return info.build();
    }

    private static Wa.HistorySync sampleSync() {
        Wa.Conversation dm = Wa.Conversation.newBuilder()
            .setId("123@s.whatsapp.net")
            .addMessages(Wa.HistorySyncMsg.newBuilder()
                .setMessage(webMessage("123@s.whatsapp.net", null, "MSG1", false, "hello", null)))
            .build();
        Wa.Conversation group = Wa.Conversation.newBuilder()
            .setId("456@g.us")
            .addMessages(Wa.HistorySyncMsg.newBuilder()
                .setMessage(webMessage("456@g.us", "789@s.whatsapp.net", "MSG2", false, "hi", null)))
            .build();
        return Wa.HistorySync.newBuilder()
            .setSyncType(Wa.HistorySync.HistorySyncType.RECENT)
            .setProgress(42)
            .addConversations(dm)
            .addConversations(group)
            .build();
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater();
        deflater.setInput(raw);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    private static Wa.Message.HistorySyncNotification notificationWith(
            Wa.HistorySync sync) {
        return Wa.Message.HistorySyncNotification.newBuilder()
            .setSyncType(Wa.Message.HistorySyncType.RECENT)
            .setChunkOrder(3)
            .setPeerDataRequestSessionId("sess-1")
            .setInitialHistBootstrapInlinePayload(
                ByteString.copyFrom(deflate(sync.toByteArray())))
            .build();
    }

    @Test
    void inlinePayloadInflatesAndDecodes() {
        Wa.HistorySync sync = sampleSync();
        Wa.HistorySync decoded =
            HistorySyncNotificationParser.decodeInline(notificationWith(sync));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getConversationsList()).hasSize(2);
        assertThat(decoded.getProgress()).isEqualTo(42);
    }

    @Test
    void toHistorySetMapsChatsContactsMessagesAndMetadata() {
        Wa.HistorySync sync = sampleSync();
        Wa.Message.HistorySyncNotification notif = notificationWith(sync);
        MessagingHistorySet set =
            HistorySyncNotificationParser.toHistorySet(sync, notif, true);
        assertThat(set.chatIds()).containsExactly("123@s.whatsapp.net", "456@g.us");
        assertThat(set.contactIds()).containsExactly("123@s.whatsapp.net", "456@g.us");
        assertThat(set.messages()).hasSize(2);
        assertThat(set.messages().get(0).senderJid()).isEqualTo("123@s.whatsapp.net");
        assertThat(set.messages().get(0).groupJid()).isNull();
        assertThat(set.messages().get(0).msgId()).isEqualTo("MSG1");
        assertThat(set.messages().get(0).timestamp()).isEqualTo(1700000000L);
        assertThat(set.messages().get(1).senderJid()).isEqualTo("789@s.whatsapp.net");
        assertThat(set.messages().get(1).groupJid()).isEqualTo("456@g.us");
        assertThat(set.syncType()).isEqualTo("RECENT");
        assertThat(set.progress()).isEqualTo(42L);
        assertThat(set.isLatest()).isTrue();
        assertThat(set.chunkOrder()).isEqualTo(3);
        assertThat(set.peerDataRequestSessionId()).isEqualTo("sess-1");
    }

    @Test
    void decodeInlineReturnsNullWithoutPayloadOrWithBadPayload() {
        assertThat(HistorySyncNotificationParser.decodeInline(null)).isNull();
        Wa.Message.HistorySyncNotification noPayload =
            Wa.Message.HistorySyncNotification.newBuilder()
                .setSyncType(Wa.Message.HistorySyncType.RECENT)
                .build();
        assertThat(HistorySyncNotificationParser.decodeInline(noPayload)).isNull();
        Wa.Message.HistorySyncNotification bad =
            Wa.Message.HistorySyncNotification.newBuilder()
                .setInitialHistBootstrapInlinePayload(
                    ByteString.copyFromUtf8("not-zlib-and-not-protobuf"))
                .build();
        assertThat(HistorySyncNotificationParser.decodeInline(bad)).isNull();
    }

    @Test
    void extractNotificationUnwrapsEphemeralAndViewOnce() {
        Wa.Message.HistorySyncNotification notif =
            Wa.Message.HistorySyncNotification.newBuilder()
                .setSyncType(Wa.Message.HistorySyncType.RECENT)
                .build();
        Wa.Message inner = Wa.Message.newBuilder()
            .setProtocolMessage(Wa.Message.ProtocolMessage.newBuilder()
                .setHistorySyncNotification(notif))
            .build();
        assertThat(HistorySyncNotificationParser.extractNotification(inner)).isEqualTo(notif);
        Wa.Message ephemeral = Wa.Message.newBuilder()
            .setEphemeralMessage(Wa.Message.FutureProofMessage.newBuilder().setMessage(inner))
            .build();
        assertThat(HistorySyncNotificationParser.extractNotification(ephemeral)).isEqualTo(notif);
        Wa.Message viewOnce = Wa.Message.newBuilder()
            .setViewOnceMessageV2(Wa.Message.FutureProofMessage.newBuilder().setMessage(inner))
            .build();
        assertThat(HistorySyncNotificationParser.extractNotification(viewOnce)).isEqualTo(notif);
        assertThat(HistorySyncNotificationParser.extractNotification(null)).isNull();
        assertThat(HistorySyncNotificationParser.extractNotification(
            Wa.Message.newBuilder().setConversation("plain").build())).isNull();
    }

    @Test
    void shouldProcessGateMatchesBaileysDefaults() {
        assertThat(HistorySyncNotificationParser.shouldProcessHistoryMessage(
            Wa.Message.HistorySyncType.INITIAL_BOOTSTRAP)).isTrue();
        assertThat(HistorySyncNotificationParser.shouldProcessHistoryMessage(
            Wa.Message.HistorySyncType.RECENT)).isTrue();
        assertThat(HistorySyncNotificationParser.shouldProcessHistoryMessage(
            Wa.Message.HistorySyncType.PUSH_NAME)).isTrue();
        assertThat(HistorySyncNotificationParser.shouldProcessHistoryMessage(
            Wa.Message.HistorySyncType.ON_DEMAND)).isTrue();
        assertThat(HistorySyncNotificationParser.shouldProcessHistoryMessage(
            Wa.Message.HistorySyncType.FULL)).isFalse();
        assertThat(HistorySyncNotificationParser.shouldProcessHistoryMessage(
            Wa.Message.HistorySyncType.NO_HISTORY)).isFalse();
        assertThat(HistorySyncNotificationParser.shouldProcessHistoryMessage(null)).isFalse();
    }

    @Test
    void lidPnMappingsCoverExplicitConversationAndReceiptFallback() {
        Wa.HistorySync sync = Wa.HistorySync.newBuilder()
            .addPhoneNumberToLidMappings(Wa.PhoneNumberToLIDMapping.newBuilder()
                .setLidJid("10@lid").setPnJid("11@s.whatsapp.net"))
            .addConversations(Wa.Conversation.newBuilder()
                .setId("20@lid").setPnJid("21@s.whatsapp.net"))
            .addConversations(Wa.Conversation.newBuilder()
                .setId("30@s.whatsapp.net").setLidJid("31@lid"))
            .addConversations(Wa.Conversation.newBuilder()
                .setId("40@lid")
                .addMessages(Wa.HistorySyncMsg.newBuilder().setMessage(
                    webMessage("40@lid", null, "M9", true, "x", "41@s.whatsapp.net"))))
            .build();
        List<LidMappingUpdate> mappings = HistorySyncNotificationParser.lidPnMappings(sync);
        assertThat(mappings).containsExactly(
            new LidMappingUpdate("10@lid", "11@s.whatsapp.net"),
            new LidMappingUpdate("20@lid", "21@s.whatsapp.net"),
            new LidMappingUpdate("31@lid", "30@s.whatsapp.net"),
            new LidMappingUpdate("40@lid", "41@s.whatsapp.net"));
    }

    @Test
    void decodeDownloadedInflatesAndDecodesDownloadBranchPayload() {
        Wa.HistorySync sync = sampleSync();
        Wa.HistorySync decoded =
            HistorySyncNotificationParser.decodeDownloaded(deflate(sync.toByteArray()));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getConversationsList()).hasSize(2);
        assertThat(decoded.getProgress()).isEqualTo(42);
    }

    @Test
    void decodeDownloadedRejectsNullEmptyAndGarbage() {
        assertThat(HistorySyncNotificationParser.decodeDownloaded(null)).isNull();
        assertThat(HistorySyncNotificationParser.decodeDownloaded(new byte[0])).isNull();
        assertThat(HistorySyncNotificationParser.decodeDownloaded(
            "not-zlib-and-not-protobuf".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isNull();
    }

    @Test
    void historyMediaTypeCarriesBaileysHistoryHkdfInfo() {
        assertThat(new String(id.ja_wa.feature.media.MediaCrypto.MediaType.HISTORY.info(),
            java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("WhatsApp History Keys");
        id.ja_wa.feature.media.MediaCrypto.ExpandedKey key =
            id.ja_wa.feature.media.MediaCrypto.expandKey(new byte[32],
                id.ja_wa.feature.media.MediaCrypto.MediaType.HISTORY);
        assertThat(key.iv()).hasSize(16);
        assertThat(key.cipherKey()).hasSize(32);
        assertThat(key.macKey()).hasSize(32);
        assertThat(key.refKey()).hasSize(32);
    }

    @Test
    void downloadHistoryIsNullSafeWithoutNetwork() {
        assertThat(HistorySyncNotificationParser.downloadHistory(null)).isNull();
        // No directPath: Baileys has no URL to download from, so this
        // must return null without touching the network.
        Wa.Message.HistorySyncNotification noPath =
            Wa.Message.HistorySyncNotification.newBuilder()
                .setSyncType(Wa.Message.HistorySyncType.RECENT)
                .setMediaKey(com.google.protobuf.ByteString.copyFrom(new byte[32]))
                .build();
        assertThat(HistorySyncNotificationParser.downloadHistory(noPath)).isNull();
        // No mediaKey: same, no network.
        Wa.Message.HistorySyncNotification noKey =
            Wa.Message.HistorySyncNotification.newBuilder()
                .setSyncType(Wa.Message.HistorySyncType.RECENT)
                .setDirectPath("/path/to/history")
                .build();
        assertThat(HistorySyncNotificationParser.downloadHistory(noKey)).isNull();
        Wa.Message.HistorySyncNotification blankPath =
            Wa.Message.HistorySyncNotification.newBuilder()
                .setSyncType(Wa.Message.HistorySyncType.RECENT)
                .setDirectPath("   ")
                .setMediaKey(com.google.protobuf.ByteString.copyFrom(new byte[32]))
                .build();
        assertThat(HistorySyncNotificationParser.downloadHistory(blankPath)).isNull();
    }

    @Test
    void pushnameSyncContributesContactsOnly() {
        Wa.HistorySync sync = Wa.HistorySync.newBuilder()
            .setSyncType(Wa.HistorySync.HistorySyncType.PUSH_NAME)
            .addPushnames(Wa.Pushname.newBuilder().setId("55@s.whatsapp.net").setPushname("Ana"))
            .build();
        MessagingHistorySet set = HistorySyncNotificationParser.toHistorySet(sync, null, false);
        assertThat(set.chatIds()).isEmpty();
        assertThat(set.contactIds()).containsExactly("55@s.whatsapp.net");
        assertThat(set.messages()).isEmpty();
        assertThat(set.syncType()).isEqualTo("PUSH_NAME");
    }
}
