package id.jawa.client;

import id.jawa.domain.model.AuthCreds;
import id.jawa.domain.store.FileAuthStore;
import id.jawa.event.BaileysEventType;
import id.jawa.event.MediaRetryParser;
import id.jawa.event.MediaRetryRequest;
import id.jawa.event.MediaUpdateWaiter;
import id.jawa.event.MessagesMediaUpdate;
import id.jawa.event.MessageUpsert;
import id.jawa.event.WaEventBus;
import id.jawa.event.WaEventListener;
import id.jawa.feature.messaging.MessageReceiver;
import id.jawa.proto.Wa;
import id.jawa.protocol.codec.BinaryNode;
import id.jawa.protocol.crypto.AesGcm;
import id.jawa.protocol.crypto.Bytes;
import id.jawa.protocol.crypto.Hkdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Increment 9: Decoded stub fields + community-V4 synthetic fidelity,
 * and the portable part of Baileys {@code updateMediaMessage}.
 * No live server is used: the V4 accept goes through the fake IQ
 * sender (as in Increment8Test), the media-retry crypto is exercised
 * with a real HKDF/AES-GCM round-trip, and the media-update waiter
 * is driven through the event bus. Task 3 (ciphertext-stub
 * placeholder upsert) is intentionally NOT covered here: it was
 * stopped on its documented blocker (see the report), so no test
 * claims it.
 */
class Increment9Test {

    private static JaWaClient clientWithFakeIq(Path tempDir, List<BinaryNode> sent) throws Exception {
        JaWaClient client = new JaWaClient(new FileAuthStore(tempDir.resolve("creds.session")));
        client.setIqSenderForTesting(iq -> {
            sent.add(iq);
            return CompletableFuture.completedFuture(
                new BinaryNode("iq", Map.of("type", "result", "from", "123@g.us"), null));
        });
        return client;
    }

    private static void assertListenerCountDrains(WaEventBus bus) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (bus.listenerCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(bus.listenerCount()).isEqualTo(0);
    }

    private static byte[] mediaKey() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 1);
        return key;
    }

    // ---- Task 1: Decoded stub fields ----

    @Test
    void decodedStubFieldsDefaultNullOnBothLegacyConstructors() {
        MessageReceiver.Decoded seven = new MessageReceiver.Decoded(
            "999@s.whatsapp.net", "123@g.us", "MSG1", "skmsg", null, "hi", null);
        assertThat(seven.messageStubType()).isNull();
        assertThat(seven.messageStubParameters()).isNull();

        MessageReceiver.Decoded eight = new MessageReceiver.Decoded(
            "999@s.whatsapp.net", "123@g.us", "MSG1", "skmsg", null, "hi", null, 1700000000L);
        assertThat(eight.messageStubType()).isNull();
        assertThat(eight.messageStubParameters()).isNull();
        assertThat(eight.timestamp()).isEqualTo(1700000000L);
    }

    @Test
    void meStubParameterJsonShapeAndFieldDifferences() {
        AuthCreds creds = AuthCreds.generate();
        creds.meJid = "111:5@s.whatsapp.net";
        creds.pushName = "Javed";
        creds.meLid = "222@lid";
        // Baileys key order id/name/lid; meJid is bared (device stripped) like Baileys' me.id.
        assertThat(JaWaClient.meStubParameterJson(creds))
            .isEqualTo("{\"id\":\"111@s.whatsapp.net\",\"name\":\"Javed\",\"lid\":\"222@lid\"}");

        AuthCreds bare = AuthCreds.generate();
        bare.meJid = "111@s.whatsapp.net";
        assertThat(JaWaClient.meStubParameterJson(bare))
            .isEqualTo("{\"id\":\"111@s.whatsapp.net\"}");

        assertThat(JaWaClient.meStubParameterJson(null)).isEqualTo("{}");
    }

    @Test
    void v4SyntheticUpsertCarriesGroupParticipantAddStub(@TempDir Path tempDir) throws Exception {
        JaWaClient client = clientWithFakeIq(tempDir, new ArrayList<>());
        AuthCreds creds = AuthCreds.generate();
        creds.meJid = "111:5@s.whatsapp.net";
        creds.pushName = "Javed";
        creds.meLid = "222@lid";
        client.setCredsForTesting(creds);
        List<MessageUpsert> upserts = new ArrayList<>();
        client.addEventListener(new WaEventListener() {
            @Override
            public void onMessageUpsert(MessageUpsert upsert) {
                upserts.add(upsert);
            }
        });
        Wa.Message.GroupInviteMessage invite = Wa.Message.GroupInviteMessage.newBuilder()
            .setGroupJid("123@g.us").setInviteCode("CODE").setInviteExpiration(9999L).build();

        client.acceptCommunityInviteV4("999@s.whatsapp.net", "INV1", false, null, invite).join();

        assertThat(upserts).hasSize(1);
        MessageReceiver.Decoded synthetic = upserts.get(0).messages().get(0);
        assertThat(synthetic.messageStubType())
            .isEqualTo(Wa.WebMessageInfo.StubType.GROUP_PARTICIPANT_ADD);
        assertThat(synthetic.messageStubParameters()).containsExactly(
            "{\"id\":\"111@s.whatsapp.net\",\"name\":\"Javed\",\"lid\":\"222@lid\"}");
        // Store feed retains the stub fields: the store carries Decoded through verbatim.
        List<MessageReceiver.Decoded> stored = client.store().messages("123@g.us");
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).messageStubType())
            .isEqualTo(Wa.WebMessageInfo.StubType.GROUP_PARTICIPANT_ADD);
        assertThat(stored.get(0).messageStubParameters()).containsExactly(
            "{\"id\":\"111@s.whatsapp.net\",\"name\":\"Javed\",\"lid\":\"222@lid\"}");
    }

    // ---- Task 2: media-retry request builder ----

    @Test
    void mediaRetryRequestShapeAndServerErrorReceiptRoundTrip() throws Exception {
        byte[] key = mediaKey();
        BinaryNode node = MediaRetryRequest.build(
            "MSG7", "123@g.us", false, "999@s.whatsapp.net", key, "111@s.whatsapp.net");

        assertThat(node.tag()).isEqualTo("receipt");
        assertThat(node.attr("id")).isEqualTo("MSG7");
        assertThat(node.attr("to")).isEqualTo("111@s.whatsapp.net");
        assertThat(node.attr("type")).isEqualTo("server-error");
        BinaryNode encrypt = node.child("encrypt");
        assertThat(encrypt).isNotNull();
        byte[] ciphertext = encrypt.child("enc_p").bytesContent();
        byte[] iv = encrypt.child("enc_iv").bytesContent();
        assertThat(ciphertext).isNotEmpty();
        assertThat(iv).hasSize(12);
        BinaryNode rmr = node.child("rmr");
        assertThat(rmr.attr("jid")).isEqualTo("123@g.us");
        assertThat(rmr.attr("from_me")).isEqualTo("false");
        assertThat(rmr.attr("participant")).isEqualTo("999@s.whatsapp.net");

        // The phone-side decrypt of the request: ServerErrorReceipt{stanzaId == message id}.
        byte[] retryKey = Hkdf.derive(key, null,
            "WhatsApp Media Retry Notification".getBytes(StandardCharsets.UTF_8), 32);
        byte[] plain = AesGcm.decrypt(retryKey, iv, ciphertext,
            "MSG7".getBytes(StandardCharsets.UTF_8));
        assertThat(Wa.ServerErrorReceipt.parseFrom(plain).getStanzaId()).isEqualTo("MSG7");
    }

    @Test
    void mediaKeyOfExtractsFromMediaMessagesOnly() {
        Wa.Message image = Wa.Message.newBuilder()
            .setImageMessage(Wa.Message.ImageMessage.newBuilder()
                .setMediaKey(com.google.protobuf.ByteString.copyFrom(mediaKey())).build())
            .build();
        assertThat(MediaRetryRequest.mediaKeyOf(image)).isEqualTo(mediaKey());
        assertThat(MediaRetryRequest.mediaKeyOf(
            Wa.Message.newBuilder().setConversation("plain text").build())).isNull();
        assertThat(MediaRetryRequest.mediaKeyOf(null)).isNull();
    }

    // ---- Task 2: parser now carries ciphertext/iv; waiter correlation ----

    @Test
    void mediaRetryParserCarriesCiphertextAndIv() {
        byte[] ciphertext = Bytes.random(40);
        byte[] iv = Bytes.random(12);
        BinaryNode node = new BinaryNode("notification",
            Map.of("id", "MSG7"),
            List.of(
                new BinaryNode("rmr", Map.of("jid", "123@g.us", "from_me", "false",
                    "participant", "999@s.whatsapp.net"), null),
                new BinaryNode("encrypt", Map.of(), List.of(
                    new BinaryNode("enc_p", Map.of(), ciphertext),
                    new BinaryNode("enc_iv", Map.of(), iv)))));

        MessagesMediaUpdate parsed = MediaRetryParser.parse(node);
        MessagesMediaUpdate.MediaUpdate update = parsed.updates().get(0);
        assertThat(update.messageId()).isEqualTo("MSG7");
        assertThat(update.errorCode()).isNull();
        assertThat(update.ciphertext()).isEqualTo(ciphertext);
        assertThat(update.iv()).isEqualTo(iv);

        // Error path / legacy shape: no encrypt child -> nulls, as before.
        BinaryNode errorNode = new BinaryNode("notification",
            Map.of("id", "MSG8"),
            List.of(
                new BinaryNode("rmr", Map.of("jid", "123@g.us", "from_me", "true"), null),
                new BinaryNode("error", Map.of("code", "404"), null)));
        MessagesMediaUpdate.MediaUpdate errorUpdate = MediaRetryParser.parse(errorNode).updates().get(0);
        assertThat(errorUpdate.errorCode()).isEqualTo(404);
        assertThat(errorUpdate.ciphertext()).isNull();
        assertThat(errorUpdate.iv()).isNull();

        // Legacy 5-arg construction still defaults the new fields to null.
        MessagesMediaUpdate.MediaUpdate legacy =
            new MessagesMediaUpdate.MediaUpdate("M", "123@g.us", false, null, null);
        assertThat(legacy.ciphertext()).isNull();
        assertThat(legacy.iv()).isNull();
    }

    @Test
    void mediaUpdateWaiterCompletesOnMatchingUpdateIgnoresOthersAndTimesOut() throws Exception {
        WaEventBus bus = new WaEventBus();
        CompletableFuture<MessagesMediaUpdate.MediaUpdate> waiting =
            MediaUpdateWaiter.waitFor(bus, "MSG7", 5_000L);

        bus.dispatch(BaileysEventType.MESSAGES_MEDIA_UPDATE,
            new MessagesMediaUpdate(List.of(
                new MessagesMediaUpdate.MediaUpdate("OTHER", "123@g.us", false, null, null))));
        assertThat(waiting.isDone()).isFalse();

        MessagesMediaUpdate.MediaUpdate match =
            new MessagesMediaUpdate.MediaUpdate("MSG7", "123@g.us", false, null, null);
        bus.dispatch(BaileysEventType.MESSAGES_MEDIA_UPDATE,
            new MessagesMediaUpdate(List.of(match)));
        assertThat(waiting.get(5, TimeUnit.SECONDS)).isSameAs(match);
        assertListenerCountDrains(bus);

        CompletableFuture<MessagesMediaUpdate.MediaUpdate> timedOut =
            MediaUpdateWaiter.waitFor(bus, "NEVER", 50L);
        assertThat(timedOut.get(5, TimeUnit.SECONDS)).isNull();
        // Listener removal runs as a completion dependent, so it can
        // land just after get() returns; wait for it rather than
        // asserting a racy instantaneous count.
        assertListenerCountDrains(bus);

        assertThatThrownBy(() -> MediaUpdateWaiter.waitFor(bus, "  ", 100L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaUpdateWaiter.waitFor(bus, "MSG7", -1L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mediaRetryNotificationDecryptsFromSimulatedPhoneReply() {
        byte[] key = mediaKey();
        Wa.MediaRetryNotification notification = Wa.MediaRetryNotification.newBuilder()
            .setStanzaId("MSG7")
            .setDirectPath("/v/t62/reuploaded_media")
            .setResult(Wa.MediaRetryNotification.ResultType.SUCCESS)
            .build();
        byte[] retryKey = Hkdf.derive(key, null,
            "WhatsApp Media Retry Notification".getBytes(StandardCharsets.UTF_8), 32);
        byte[] iv = Bytes.random(12);
        byte[] ciphertext = AesGcm.encrypt(retryKey, iv, notification.toByteArray(),
            "MSG7".getBytes(StandardCharsets.UTF_8));

        Wa.MediaRetryNotification decrypted =
            JaWaClient.decryptMediaRetryNotification(ciphertext, iv, "MSG7", key);
        assertThat(decrypted.getResult()).isEqualTo(Wa.MediaRetryNotification.ResultType.SUCCESS);
        assertThat(decrypted.getDirectPath()).isEqualTo("/v/t62/reuploaded_media");
    }

    // ---- Task 2: updateMediaMessage validation + disconnected behaviour ----

    @Test
    void updateMediaMessageValidationAndDisconnectedTimeout(@TempDir Path tempDir) throws Exception {
        JaWaClient client = new JaWaClient(new FileAuthStore(tempDir.resolve("creds.session")));
        AuthCreds creds = AuthCreds.generate();
        creds.meJid = "111@s.whatsapp.net";
        client.setCredsForTesting(creds);

        Wa.Message text = Wa.Message.newBuilder().setConversation("no media here").build();
        assertThatThrownBy(() ->
            client.updateMediaMessage("MSG7", "123@g.us", false, null, text, 100L).join())
            .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            client.updateMediaMessage("MSG7", "123@g.us", false, null, new byte[0], 100L).join())
            .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
            client.updateMediaMessage("MSG7", "123@g.us", false, null, mediaKey(), -1L).join())
            .hasRootCauseInstanceOf(IllegalArgumentException.class);

        // Disconnected: the retry request is dropped by the unchanged
        // send() no-op, so the call resolves null when the wait times out
        // instead of failing or hanging.
        Wa.MediaRetryNotification result = client.updateMediaMessage(
            "MSG7", "123@g.us", false, null, mediaKey(), 150L).get(5, TimeUnit.SECONDS);
        assertThat(result).isNull();

        // Facade delegates with the same contract.
        Wa.MediaRetryNotification viaFacade = client.messages().updateMediaMessage(
            "MSG7", "123@g.us", false, null, mediaKey(), 150L).get(5, TimeUnit.SECONDS);
        assertThat(viaFacade).isNull();
    }
}
