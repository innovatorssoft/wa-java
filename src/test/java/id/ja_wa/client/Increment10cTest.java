package id.ja_wa.client;

import id.ja_wa.domain.model.AuthCreds;
import id.ja_wa.domain.store.FileAuthStore;
import id.ja_wa.event.MessageUpsert;
import id.ja_wa.event.MessagesUpdate;
import id.ja_wa.event.WaEventListener;
import id.ja_wa.feature.messaging.MessageReceiver;
import id.ja_wa.feature.messaging.PeerDataOperations;
import id.ja_wa.feature.signal.JaWaProtocolStore;
import id.ja_wa.proto.Wa;
import id.ja_wa.protocol.codec.BinaryNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.SessionBuilder;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.KeyHelper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Increment 10c: Baileys' {@code messages.update} on successful PDO
 * auto-send, against
 * {@code lib/Socket/messages-recv.js:1345-1354}:
 * <pre>
 * requestPlaceholderResend(cleanKey, msgData)
 *     .then(requestId => {
 *         if (requestId && requestId !== 'RESOLVED') {
 *             ev.emit('messages.update', [{
 *                 key: msg.key,
 *                 update: { messageStubParameters: [NO_MESSAGE_FOUND_ERROR_TEXT, requestId] }
 *             }]);
 *         }
 *     })
 * </pre>
 * All cases are driven through the real catch
 * ({@link JaWaClient#handleStanzaForTesting}), with the fake delay
 * from Increment10bTest, so the auto-send runs synchronously and no
 * live server or socket is used. The handler lives only on the
 * catch's auto-send future: the manual
 * {@code requestPlaceholderResend} path is asserted unchanged (it
 * emits no update) in the success test's duplicate/manual steps.
 */
class Increment10cTest {

    private static final long NOW = System.currentTimeMillis() / 1000L;

    private static BinaryNode stanza(Map<String, String> attrs, List<BinaryNode> children) {
        return new BinaryNode("message", attrs, children);
    }

    private static Map<String, String> attrs(String id, String from, long t) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("from", from);
        m.put("t", Long.toString(t));
        return m;
    }

    private static BinaryNode unavailable(String type) {
        return type == null
            ? new BinaryNode("unavailable", Map.of(), null)
            : new BinaryNode("unavailable", Map.of("type", type), null);
    }

    private static List<MessageUpsert> captureUpserts(JaWaClient client) {
        List<MessageUpsert> upserts = new ArrayList<>();
        client.addEventListener(new WaEventListener() {
            @Override
            public void onMessageUpsert(MessageUpsert upsert) {
                upserts.add(upsert);
            }
        });
        return upserts;
    }

    private static List<MessagesUpdate> captureUpdates(JaWaClient client) {
        List<MessagesUpdate> updates = new ArrayList<>();
        client.addEventListener(new WaEventListener() {
            @Override
            public void onMessagesUpdate(MessagesUpdate update) {
                updates.add(update);
            }
        });
        return updates;
    }

    /**
     * Companion client (device 5, as in Increment10bTest) with a real
     * in-memory Signal session for device 0, so the auto-send's PDO
     * stanza is captured at the peer-mode seam. The fake delay
     * completes immediately, so the auto-send (and its success
     * handler) runs synchronously inside the catch.
     */
    private static JaWaClient companionClient(Path tempDir, List<BinaryNode> captured,
            long[] seenDelay) throws Exception {
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
        client.setMessageSenderForTesting(captured::add);
        client.setPlaceholderResendDelayForTesting(millis -> {
            seenDelay[0] = millis;
            return CompletableFuture.completedFuture(null);
        });
        return client;
    }

    @Test
    void catchAutoSendSuccessEmitsMessagesUpdateWithErrorAndRequestId(@TempDir Path tempDir)
            throws Exception {
        List<BinaryNode> captured = new ArrayList<>();
        long[] seenDelay = {-1L};
        JaWaClient client = companionClient(tempDir, captured, seenDelay);
        List<MessageUpsert> upserts = captureUpserts(client);
        List<MessagesUpdate> updates = captureUpdates(client);

        BinaryNode fresh = stanza(attrs("MSG10C", "999@s.whatsapp.net", NOW),
            List.of(unavailable(null)));
        assertThat(client.handleStanzaForTesting(fresh)).isTrue();

        // Stub upsert, unchanged from Increment 10b.
        assertThat(upserts).hasSize(1);
        assertThat(upserts.get(0).type()).isEqualTo("notify");
        MessageReceiver.Decoded stub = upserts.get(0).messages().get(0);
        assertThat(stub.msgId()).isEqualTo("MSG10C");
        assertThat(stub.messageStubType())
            .isEqualTo(Wa.WebMessageInfo.StubType.CIPHERTEXT);
        assertThat(stub.messageStubParameters())
            .containsExactly("Message absent from node");
        assertThat(client.store().messages("999@s.whatsapp.net")).hasSize(1);
        assertThat(captured).hasSize(1);

        // The success-only update: same key as the stub, carrying
        // [NO_MESSAGE_FOUND_ERROR_TEXT, requestId], where requestId
        // is the sent PDO message id (the captured stanza's id).
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).updates()).hasSize(1);
        MessagesUpdate.MessageUpdate update = updates.get(0).updates().get(0);
        assertThat(update.messageId()).isEqualTo("MSG10C");
        assertThat(update.remoteJid()).isEqualTo("999@s.whatsapp.net");
        assertThat(update.fromMe()).isFalse();
        assertThat(update.participant()).isNull();
        String requestId = captured.get(0).attr("id");
        assertThat(requestId).isNotBlank();
        assertThat(requestId).isNotEqualTo("RESOLVED");
        assertThat(update.messageStubParameters())
            .containsExactly("Message absent from node", requestId);
        assertThat(update.groupInviteMessage()).isNull();

        // Duplicate arrival: still upserts, but its auto-send future
        // resolves to null (already claimed), so no second update
        // and no second PDO stanza.
        assertThat(client.handleStanzaForTesting(fresh)).isTrue();
        assertThat(upserts).hasSize(2);
        assertThat(updates).hasSize(1);
        assertThat(captured).hasSize(1);

        // Manual request observes already-claimed (null) and, as in
        // Baileys, emits no update from the manual path.
        Wa.MessageKey key = Wa.MessageKey.newBuilder()
            .setRemoteJid("999@s.whatsapp.net").setFromMe(false).setId("MSG10C").build();
        assertThat(client.requestPlaceholderResend(key, null).get()).isNull();
        assertThat(updates).hasSize(1);
        assertThat(captured).hasSize(1);
    }

    @Test
    void catchAutoSendSuccessEmitsGroupKeyForGroupMessage(@TempDir Path tempDir)
            throws Exception {
        List<BinaryNode> captured = new ArrayList<>();
        long[] seenDelay = {-1L};
        JaWaClient client = companionClient(tempDir, captured, seenDelay);
        List<MessageUpsert> upserts = captureUpserts(client);
        List<MessagesUpdate> updates = captureUpdates(client);

        Map<String, String> groupAttrs = attrs("GRP10C", "123@g.us", NOW);
        groupAttrs.put("participant", "999@s.whatsapp.net");
        BinaryNode fresh = stanza(groupAttrs, List.of(unavailable(null)));
        assertThat(client.handleStanzaForTesting(fresh)).isTrue();

        assertThat(upserts).hasSize(1);
        assertThat(captured).hasSize(1);
        assertThat(updates).hasSize(1);
        MessagesUpdate.MessageUpdate update = updates.get(0).updates().get(0);
        assertThat(update.messageId()).isEqualTo("GRP10C");
        assertThat(update.remoteJid()).isEqualTo("123@g.us");
        assertThat(update.fromMe()).isFalse();
        assertThat(update.participant()).isEqualTo("999@s.whatsapp.net");
        assertThat(update.messageStubParameters())
            .containsExactly("Message absent from node", captured.get(0).attr("id"));
    }

    @Test
    void catchAutoSendFailureEmitsStubButNoMessagesUpdate(@TempDir Path tempDir)
            throws Exception {
        // Deliberately no setCredsForTesting: creds stays null, so the
        // auto-send fails fast on "Not authenticated" (as in
        // Increment10bTest's null-creds test) and is swallowed.
        JaWaClient client = new JaWaClient(new FileAuthStore(tempDir.resolve("creds.session")));
        List<BinaryNode> captured = new ArrayList<>();
        client.setMessageSenderForTesting(captured::add);
        long[] seenDelay = {-1L};
        client.setPlaceholderResendDelayForTesting(millis -> {
            seenDelay[0] = millis;
            return CompletableFuture.completedFuture(null);
        });
        List<MessageUpsert> upserts = captureUpserts(client);
        List<MessagesUpdate> updates = captureUpdates(client);

        BinaryNode fresh = stanza(attrs("NULLC10C", "999@s.whatsapp.net", NOW),
            List.of(unavailable(null)));
        assertThat(client.handleStanzaForTesting(fresh)).isTrue();

        // Stub upsert + store + claim, unchanged; no PDO stanza.
        assertThat(upserts).hasSize(1);
        assertThat(upserts.get(0).messages().get(0).messageStubType())
            .isEqualTo(Wa.WebMessageInfo.StubType.CIPHERTEXT);
        assertThat(client.store().messages("999@s.whatsapp.net")).hasSize(1);
        assertThat(client.placeholderResendManagerForTesting().isPending("NULLC10C")).isTrue();
        assertThat(seenDelay[0]).isEqualTo(PeerDataOperations.PLACEHOLDER_RESEND_DELAY_MS);
        assertThat(captured).isEmpty();
        // Failure is logged-and-swallowed: no messages.update.
        assertThat(updates).isEmpty();
    }

    @Test
    void nonEligibleMessagesYieldNeitherStubNorUpdate(@TempDir Path tempDir)
            throws Exception {
        List<BinaryNode> captured = new ArrayList<>();
        long[] seenDelay = {-1L};
        JaWaClient client = companionClient(tempDir, captured, seenDelay);
        List<MessageUpsert> upserts = captureUpserts(client);
        List<MessagesUpdate> updates = captureUpdates(client);

        BinaryNode old = stanza(attrs("OLD10C", "999@s.whatsapp.net", NOW - 15L * 24 * 60 * 60),
            List.of(unavailable(null)));
        assertThat(client.handleStanzaForTesting(old)).isTrue();

        BinaryNode excluded = stanza(attrs("EXC10C", "999@s.whatsapp.net", NOW),
            List.of(unavailable("hosted_unavailable_fanout")));
        assertThat(client.handleStanzaForTesting(excluded)).isTrue();

        Map<String, String> peerAttrs = attrs("PEER10C", "999@s.whatsapp.net", NOW);
        peerAttrs.put("category", "peer");
        BinaryNode peer = stanza(peerAttrs, List.of(unavailable(null)));
        assertThat(client.handleStanzaForTesting(peer)).isTrue();

        BinaryNode other = stanza(attrs("BAD10C", "999@s.whatsapp.net", NOW),
            List.of(new BinaryNode("enc", Map.of("type", "msg"), new byte[0])));
        assertThat(client.handleStanzaForTesting(other)).isTrue();

        assertThat(upserts).isEmpty();
        assertThat(updates).isEmpty();
        assertThat(captured).isEmpty();
        assertThat(seenDelay[0]).isEqualTo(-1L);
        for (String id : List.of("OLD10C", "EXC10C", "PEER10C", "BAD10C")) {
            assertThat(client.placeholderResendManagerForTesting().isPending(id))
                .as("pending %s", id).isFalse();
        }
        assertThat(client.store().messages("999@s.whatsapp.net")).isEmpty();
    }
}
