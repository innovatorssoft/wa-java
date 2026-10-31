package id.ja_wa.client;

import id.ja_wa.domain.model.AuthCreds;
import id.ja_wa.domain.store.FileAuthStore;
import id.ja_wa.feature.appstate.AppPatchBuilder;
import id.ja_wa.feature.appstate.AppStateKey;
import id.ja_wa.feature.appstate.FileAppStateKeyStorage;
import id.ja_wa.feature.appstate.InMemoryAppStateVersionStore;
import id.ja_wa.feature.messaging.CallLinkQuery;
import id.ja_wa.feature.messaging.PeerDataOperations;
import id.ja_wa.feature.signal.JaWaProtocolStore;
import id.ja_wa.proto.Wa;
import id.ja_wa.protocol.codec.BinaryNode;
import id.ja_wa.protocol.crypto.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.SessionBuilder;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.KeyHelper;

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
 * Scoped fail-fast (2026-10-27 decision): futures returned to callers
 * that await a result must fail promptly when the client is
 * disconnected, instead of staying pending or completing with a
 * successful-looking id. {@link JaWaClient#send(BinaryNode)} itself
 * is unchanged (void, drop-and-log). No live server or socket is
 * used: a fresh client has no transport/frame, which is exactly the
 * disconnected condition {@code send()} checks.
 */
class ScopedFailFastTest {

    private static JaWaClient disconnectedClient(Path tempDir) {
        return new JaWaClient(new FileAuthStore(tempDir.resolve("creds.session")));
    }

    private static BinaryNode iq(String id) {
        return new BinaryNode("iq", Map.of("id", id, "type", "get"), null);
    }

    @Test
    void sendIqAsyncFailsPromptlyWhenDisconnected(@TempDir Path tempDir) {
        JaWaClient client = disconnectedClient(tempDir);
        CompletableFuture<BinaryNode> future = client.sendIqAsync(iq("iq-1"));
        assertThat(future.isDone()).isTrue();
        assertThat(future.isCompletedExceptionally()).isTrue();
        assertThatThrownBy(future::join)
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Not connected");
    }

    @Test
    void sendIqAsyncSeamTakesPrecedenceWhenDisconnected(@TempDir Path tempDir) {
        JaWaClient client = disconnectedClient(tempDir);
        BinaryNode reply = new BinaryNode("iq", Map.of("type", "result"), null);
        client.setIqSenderForTesting(sent -> CompletableFuture.completedFuture(reply));
        assertThat(client.sendIqAsync(iq("iq-2")).join()).isSameAs(reply);
    }

    @Test
    void sendStanzaAsyncStaysPendingWhenDisconnected(@TempDir Path tempDir) {
        // Deliberately retained: sendStanzaAsync is NOT guarded, so the
        // pending-correlation pattern driven through handleStanza keeps
        // working (see Increment6WorkstreamBTest). This pins the
        // retention so a future guard does not silently change it.
        JaWaClient client = disconnectedClient(tempDir);
        BinaryNode request = CallLinkQuery.buildCreate("call-req-1", "video", null);
        CompletableFuture<BinaryNode> pending = client.sendStanzaAsync(request);
        assertThat(pending.isDone()).isFalse();
    }

    @Test
    void newsletterSendsFailInsteadOfReturningCompletedIdWhenDisconnected(@TempDir Path tempDir) {
        JaWaClient client = disconnectedClient(tempDir);
        Wa.Message msg = Wa.Message.newBuilder().setConversation("hi").build();

        assertThatThrownBy(() -> client.sendNewsletterMessage("123@newsletter", msg).join())
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Not connected");
        assertThatThrownBy(() -> client.sendNewsletterText("123@newsletter", "hi").join())
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Not connected");
        assertThatThrownBy(() -> client.sendNewsletterReaction("123@newsletter", "42", "❤️").join())
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Not connected");
    }

    @Test
    void voidSendStillDropsAndLogsUnchanged(@TempDir Path tempDir) {
        JaWaClient client = disconnectedClient(tempDir);
        // No throw, no future to fail: the void drop-and-log behaviour
        // of send() is unchanged by the scoped fail-fast guards.
        client.send(new BinaryNode("iq", Map.of("id", "iq-3", "type", "get"), null));
        // And a guard still fires afterwards: send() did not connect us.
        assertThat(client.sendIqAsync(iq("iq-4")).isCompletedExceptionally()).isTrue();
    }

    @Test
    void appPatchFailsPromptlyWhenDisconnected(@TempDir Path tempDir) throws Exception {
        // Same fixture shape as JaWaClientAppPatchTest, but WITHOUT the
        // fake appPatch IQ sender, so the real sendIqAsync guard is the
        // sender. Resync is a no-op (no app-state processor), so the
        // failure must come from the send stage of the transport.
        byte[] keyId = "mykeyid1234567890".getBytes(StandardCharsets.UTF_8);
        JaWaClient client = disconnectedClient(tempDir);
        AuthCreds creds = AuthCreds.generate();
        creds.myAppStateKeyId = Bytes.toBase64(keyId);
        client.setCredsForTesting(creds);
        FileAppStateKeyStorage keys = new FileAppStateKeyStorage(tempDir.resolve("appstate-keys"));
        byte[] keyData = new byte[32];
        for (int i = 0; i < 32; i++) keyData[i] = (byte) i;
        keys.put(new AppStateKey(keyId, keyData));
        client.setAppStateKeyStorageForTesting(keys);
        client.setAppStateVersionStoreForTesting(new InMemoryAppStateVersionStore());

        assertThatThrownBy(() -> client.appPatch(
                AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net")).join())
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Not connected");
    }

    @Test
    void placeholderResendFailsPromptlyWhenDisconnectedWithoutSeam(@TempDir Path tempDir) throws Exception {
        JaWaClient client = disconnectedClient(tempDir);
        AuthCreds creds = AuthCreds.generate();
        creds.meJid = "111:5@s.whatsapp.net";
        client.setCredsForTesting(creds);
        // Immediate delay: the sender stage runs at once instead of
        // after the production 2-second delay.
        client.setPlaceholderResendDelayForTesting(millis -> CompletableFuture.completedFuture(null));

        Wa.MessageKey key = Wa.MessageKey.newBuilder()
            .setId("MSG-FF").setRemoteJid("222@s.whatsapp.net").build();
        assertThatThrownBy(() -> client.requestPlaceholderResend(key, null).get(5, TimeUnit.SECONDS))
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Not connected");
    }

    @Test
    void peerSendWithCaptureSeamStillCapturesWhenDisconnected(@TempDir Path tempDir) throws Exception {
        // Companion client (device 5) with a real in-memory Signal
        // session for device 0, as in Increment8Test: the capture seam
        // stands in for the transport, so the PDO guard must not fire.
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

        JaWaClient client = disconnectedClient(tempDir);
        client.setCredsForTesting(creds);
        client.setProtocolStoreForTesting(store);
        List<BinaryNode> captured = new ArrayList<>();
        client.setMessageSenderForTesting(captured::add);

        Wa.Message.PeerDataOperationRequestMessage pdo =
            PeerDataOperations.buildPlaceholderResendRequest(
                Wa.MessageKey.newBuilder().setId("MSG-FF2").setRemoteJid("222@s.whatsapp.net").build());
        String msgId = client.sendPeerDataOperationMessage(pdo).join();
        assertThat(msgId).isNotBlank();
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).attr("category")).isEqualTo("peer");
    }
}
