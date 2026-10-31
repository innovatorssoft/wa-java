package id.ja_wa.client;

import id.ja_wa.domain.model.AuthCreds;
import id.ja_wa.domain.store.FileAuthStore;
import id.ja_wa.event.MessageUpsert;
import id.ja_wa.event.WaEventListener;
import id.ja_wa.feature.messaging.MessageReceiver;
import id.ja_wa.feature.messaging.MessageSender;
import id.ja_wa.feature.messaging.PeerDataOperations;
import id.ja_wa.feature.signal.JaWaProtocolStore;
import id.ja_wa.proto.Wa;
import id.ja_wa.protocol.codec.BinaryNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.whispersystems.libsignal.SessionBuilder;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.KeyHelper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Increment 8: peer-mode live PDO wiring, MessageUpsert.requestId,
 * and the community V4 synthetic upsert. No live server is used:
 * the peer stanza is captured through a test seam after a real
 * in-memory Signal session for device 0 is installed, the PDO
 * dispatch is driven directly, and the V4 accept goes through the
 * fake IQ sender.
 */
class Increment8Test {

    private static JaWaClient clientWithFakeIq(Path tempDir, List<BinaryNode> sent) throws Exception {
        JaWaClient client = new JaWaClient(new FileAuthStore(tempDir.resolve("creds.session")));
        client.setIqSenderForTesting(iq -> {
            sent.add(iq);
            return CompletableFuture.completedFuture(
                new BinaryNode("iq", Map.of("type", "result", "from", "123@g.us"), null));
        });
        return client;
    }

    private static Wa.Message.PeerDataOperationRequestResponseMessage pdoResponse(String stanzaId) {
        Wa.WebMessageInfo reply = Wa.WebMessageInfo.newBuilder()
            .setKey(Wa.MessageKey.newBuilder()
                .setId("MSG9").setRemoteJid("222@s.whatsapp.net").setFromMe(false).build())
            .setMessage(Wa.Message.newBuilder().setConversation("hello again").build())
            .setMessageTimestamp(1700000001L)
            .build();
        Wa.Message.PeerDataOperationRequestResponseMessage.Builder b =
            Wa.Message.PeerDataOperationRequestResponseMessage.newBuilder()
                .addPeerDataOperationResult(Wa.Message.PeerDataOperationRequestResponseMessage.PeerDataOperationResult
                    .newBuilder()
                    .setPlaceholderMessageResendResponse(Wa.Message.PeerDataOperationRequestResponseMessage
                        .PeerDataOperationResult.PlaceholderMessageResendResponse.newBuilder()
                        .setWebMessageInfoBytes(reply.toByteString()).build())
                    .build());
        if (stanzaId != null) b.setStanzaId(stanzaId);
        return b.build();
    }

    // ---- Task 1: peer-mode live PDO wiring ----

    @Test
    void peerModeStanzaShapeViaClient(@TempDir Path tempDir) throws Exception {
        // Companion client (device 5) with a real session for device 0.
        AuthCreds creds = AuthCreds.generate();
        creds.meJid = "111:5@s.whatsapp.net";
        creds.account = null;
        JaWaProtocolStore store = new JaWaProtocolStore(creds);
        IdentityKeyPair remoteId = KeyHelper.generateIdentityKeyPair();
        SignedPreKeyRecord signed = KeyHelper.generateSignedPreKey(remoteId, 1);
        PreKeyRecord pre = KeyHelper.generatePreKeys(1, 1).get(0);
        PreKeyBundle bundle = new PreKeyBundle(4242, 0, pre.getId(), pre.getKeyPair().getPublicKey(),
            signed.getId(), signed.getKeyPair().getPublicKey(), signed.getSignature(),
            remoteId.getPublicKey());
        new SessionBuilder(store, new SignalProtocolAddress("111", 0)).process(bundle);

        JaWaClient client = new JaWaClient(new FileAuthStore(tempDir.resolve("creds.session")));
        client.setCredsForTesting(creds);
        client.setProtocolStoreForTesting(store);
        List<BinaryNode> captured = new ArrayList<>();
        client.setMessageSenderForTesting(captured::add);

        Wa.Message.PeerDataOperationRequestMessage pdo =
            PeerDataOperations.buildPlaceholderResendRequest(
                Wa.MessageKey.newBuilder().setId("MSG9").setRemoteJid("222@s.whatsapp.net").build());
        String msgId = client.sendPeerDataOperationMessage(pdo).join();

        assertThat(captured).hasSize(1);
        BinaryNode stanza = captured.get(0);
        assertThat(stanza.tag()).isEqualTo("message");
        assertThat(stanza.attr("to")).isEqualTo("111@s.whatsapp.net");
        assertThat(stanza.attr("id")).isEqualTo(msgId);
        assertThat(stanza.attr("category")).isEqualTo("peer");
        assertThat(stanza.attr("push_priority")).isEqualTo("high_force");
        assertThat(stanza.child("participants")).isNull();
        List<BinaryNode> children = stanza.childrenList();
        assertThat(children.get(0).tag()).isEqualTo("enc");
        assertThat(children.get(0).attr("v")).isEqualTo("2");
        assertThat(children.get(0).bytesContent()).isNotEmpty();
        // No creds.account, so no device-identity child; the meta node follows the enc.
        assertThat(children.get(children.size() - 1).tag()).isEqualTo("meta");
        assertThat(children.get(children.size() - 1).attr("appdata")).isEqualTo("default");
    }

    @Test
    void peerStanzaRefusesDeviceZeroSender() {
        AuthCreds creds = AuthCreds.generate();
        creds.meJid = "111@s.whatsapp.net"; // device 0 == sender itself
        JaWaProtocolStore store = new JaWaProtocolStore(creds);
        assertThatThrownBy(() -> MessageSender.buildPeerStanza(store, creds, "M1",
            "111@s.whatsapp.net", Wa.Message.newBuilder().setConversation("x").build(), List.of()))
            .isInstanceOf(IllegalStateException.class);
    }

    // ---- Task 2: MessageUpsert.requestId ----

    @Test
    void pdoResponseUpsertCarriesStanzaIdAndNormalUpsertIsNull(@TempDir Path tempDir) throws Exception {
        JaWaClient client = clientWithFakeIq(tempDir, new ArrayList<>());
        List<MessageUpsert> upserts = new ArrayList<>();
        client.addEventListener(new WaEventListener() {
            @Override
            public void onMessageUpsert(MessageUpsert upsert) {
                upserts.add(upsert);
            }
        });
        MessageReceiver.Decoded stored = new MessageReceiver.Decoded(
            "222@s.whatsapp.net", null, "MSG9", "msg",
            Wa.Message.newBuilder().setConversation("old").build(), "old", null, 111L);
        assertThat(client.placeholderResendManagerForTesting().tryClaim("MSG9", stored)).isTrue();

        client.dispatchPlaceholderResendResponses(pdoResponse("PDO1"));
        assertThat(upserts).hasSize(1);
        assertThat(upserts.get(0).requestId()).isEqualTo("PDO1");
        assertThat(upserts.get(0).type()).isEqualTo("notify");

        // Blank stanza id -> null, preserving the default for non-PDO upserts.
        assertThat(client.placeholderResendManagerForTesting().tryClaim("MSG9", stored)).isTrue();
        client.dispatchPlaceholderResendResponses(pdoResponse(null));
        assertThat(upserts).hasSize(2);
        assertThat(upserts.get(1).requestId()).isNull();

        // The pre-existing two-argument construction (the normal inbound path) still defaults to null.
        assertThat(new MessageUpsert(List.of(stored), "notify").requestId()).isNull();
        assertThat(new MessageUpsert(List.of(stored), "notify", "R").requestId()).isEqualTo("R");
    }

    // ---- Task 3: V4 synthetic upsert ----

    @Test
    void v4SyntheticUpsertShapeDispatchAndStoreFeed(@TempDir Path tempDir) throws Exception {
        JaWaClient client = clientWithFakeIq(tempDir, new ArrayList<>());
        AuthCreds creds = AuthCreds.generate();
        creds.meJid = "111:5@s.whatsapp.net";
        client.setCredsForTesting(creds);
        List<MessageUpsert> upserts = new ArrayList<>();
        List<Object> updates = new ArrayList<>();
        client.addEventListener(new WaEventListener() {
            @Override
            public void onMessageUpsert(MessageUpsert upsert) {
                upserts.add(upsert);
            }
        });
        Wa.Message.GroupInviteMessage invite = Wa.Message.GroupInviteMessage.newBuilder()
            .setGroupJid("123@g.us").setInviteCode("CODE").setInviteExpiration(9999L).build();

        String from = client.acceptCommunityInviteV4("999@s.whatsapp.net", "INV1", false, null, invite).join();
        assertThat(from).isEqualTo("123@g.us");
        assertThat(upserts).hasSize(1);
        MessageReceiver.Decoded synthetic = upserts.get(0).messages().get(0);
        assertThat(upserts.get(0).type()).isEqualTo("notify");
        assertThat(synthetic.senderJid()).isEqualTo("999@s.whatsapp.net");
        assertThat(synthetic.groupJid()).isEqualTo("123@g.us");
        assertThat(synthetic.msgId()).startsWith("3EB0").contains("STARFALL");
        assertThat(synthetic.message()).isNull();
        assertThat(synthetic.timestamp()).isGreaterThan(0L);
        // Store feed: the synthetic message is retrievable under the community JID.
        assertThat(client.store().messages("123@g.us")).contains(synthetic);
        assertThat(client.store().getChat("123@g.us")).isNotNull();

        // Bare-JID key: no expiry update path is exercised here, but the
        // synthetic upsert still runs (Baileys upserts unconditionally).
        client.acceptCommunityInviteV4("999@s.whatsapp.net", null, false, null, invite).join();
        assertThat(upserts).hasSize(2);
        assertThat(client.store().messages("123@g.us")).hasSize(2);
    }

    @Test
    void generateMessageIdV2Shape(@TempDir Path tempDir) throws Exception {
        JaWaClient client = clientWithFakeIq(tempDir, new ArrayList<>());
        AuthCreds creds = AuthCreds.generate();
        creds.meJid = "111:5@s.whatsapp.net";
        client.setCredsForTesting(creds);
        String id = client.generateMessageIdV2();
        assertThat(id).startsWith("3EB0").contains("STARFALL");
        assertThat(id).hasSize(30);
    }
}
