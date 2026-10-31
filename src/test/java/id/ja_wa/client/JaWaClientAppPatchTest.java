package id.ja_wa.client;

import id.ja_wa.domain.model.AuthCreds;
import id.ja_wa.domain.store.FileAuthStore;
import id.ja_wa.feature.appstate.AppPatchBuilder;
import id.ja_wa.feature.appstate.AppPatchEncoder;
import id.ja_wa.feature.appstate.AppPatchTransport;
import id.ja_wa.feature.appstate.AppStateKey;
import id.ja_wa.feature.appstate.FileAppStateKeyStorage;
import id.ja_wa.feature.appstate.InMemoryAppStateVersionStore;
import id.ja_wa.proto.Wa;
import id.ja_wa.protocol.codec.BinaryNode;
import id.ja_wa.protocol.crypto.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Increment 5: JaWaClient.appPatch and the no-transport ChatService
 * overloads, exercised with a fake IQ sender — never a live server.
 * The client's app-state processor is absent in these tests (no
 * connect), so the resync adapter is a documented no-op and the
 * transport encodes from the injected version store.
 */
class JaWaClientAppPatchTest {

    private static final byte[] KEY_ID = "mykeyid1234567890".getBytes(StandardCharsets.UTF_8);
    private static final String KEY_ID_B64 = Bytes.toBase64(KEY_ID);

    private record FakeIq(List<BinaryNode> sent, BinaryNode reply) implements AppPatchTransport.IqSender {
        @Override
        public CompletableFuture<BinaryNode> sendIq(BinaryNode iq) {
            sent.add(iq);
            return CompletableFuture.completedFuture(reply);
        }
    }

    private static BinaryNode resultIq() {
        return new BinaryNode("iq", Map.of("type", "result"), null);
    }

    private static BinaryNode errorIq() {
        return new BinaryNode("iq", Map.of("type", "error"), null);
    }

    private static AppStateKey testKey() {
        byte[] keyData = new byte[32];
        for (int i = 0; i < 32; i++) keyData[i] = (byte) i;
        return new AppStateKey(KEY_ID, keyData);
    }

    private static final class Fixture {
        final JaWaClient client;
        final List<BinaryNode> sent = new ArrayList<>();
        final InMemoryAppStateVersionStore versions = new InMemoryAppStateVersionStore();

        Fixture(Path tempDir, String keyIdB64, boolean withKey, BinaryNode reply) throws Exception {
            FileAuthStore store = new FileAuthStore(tempDir.resolve("creds.session"));
            client = new JaWaClient(store);
            AuthCreds creds = AuthCreds.generate();
            creds.myAppStateKeyId = keyIdB64;
            client.setCredsForTesting(creds);
            FileAppStateKeyStorage keys = new FileAppStateKeyStorage(tempDir.resolve("appstate-keys"));
            if (withKey) keys.put(testKey());
            client.setAppStateKeyStorageForTesting(keys);
            client.setAppStateVersionStoreForTesting(versions);
            client.setAppPatchIqSenderForTesting(new FakeIq(sent, reply));
        }
    }

    @Test
    void missingKeyIdFailsWithoutSending(@TempDir Path tempDir) throws Exception {
        Fixture f = new Fixture(tempDir, null, true, resultIq());
        AppPatchBuilder.AppPatch patch = AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net");
        assertThatThrownBy(() -> f.client.appPatch(patch).join())
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("myAppStateKeyId is not set");
        assertThat(f.sent).isEmpty();
        assertThat(f.versions.load("critical_unblock_low")).isNull();
    }

    @Test
    void noCredsAtAllFailsWithoutSending(@TempDir Path tempDir) {
        JaWaClient client = new JaWaClient(new FileAuthStore(tempDir.resolve("creds.session")));
        assertThatThrownBy(() -> client.appPatch(AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net")).join())
            .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownPatchTypeFailsWithoutSending(@TempDir Path tempDir) throws Exception {
        Fixture f = new Fixture(tempDir, KEY_ID_B64, true, resultIq());
        AppPatchBuilder.AppPatch patch = new AppPatchBuilder.AppPatch(
            List.of("contact", "111@s.whatsapp.net"),
            Wa.SyncActionValue.newBuilder().build(),
            "bogus_collection", 2, AppPatchBuilder.Operation.SET);
        assertThatThrownBy(() -> f.client.appPatch(patch).join())
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unknown app-state patch type");
        assertThat(f.sent).isEmpty();
    }

    @Test
    void invalidBase64KeyIdFailsWithoutSending(@TempDir Path tempDir) throws Exception {
        Fixture f = new Fixture(tempDir, "!!!not-base64!!!", true, resultIq());
        assertThatThrownBy(() -> f.client.appPatch(AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net")).join())
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not valid base64");
        assertThat(f.sent).isEmpty();
    }

    @Test
    void missingStoredKeyFailsWithoutPersisting(@TempDir Path tempDir) throws Exception {
        Fixture f = new Fixture(tempDir, KEY_ID_B64, false, resultIq());
        assertThatThrownBy(() -> f.client.appPatch(AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net")).join())
            .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(f.versions.load("critical_unblock_low")).isNull();
    }

    @Test
    void errorIqLeavesVersionUnpersisted(@TempDir Path tempDir) throws Exception {
        Fixture f = new Fixture(tempDir, KEY_ID_B64, true, errorIq());
        assertThatThrownBy(() -> f.client.appPatch(AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net")).join())
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("rejected by server");
        assertThat(f.sent).hasSize(1);
        assertThat(f.versions.load("critical_unblock_low")).isNull();
    }

    @Test
    void successSendsCorrectIqAndPersistsVersion(@TempDir Path tempDir) throws Exception {
        Fixture f = new Fixture(tempDir, KEY_ID_B64, true, resultIq());
        AppPatchEncoder.EncodedPatch result =
            f.client.appPatch(AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net")).get();
        assertThat(result.state().version()).isEqualTo(1L);
        assertThat(f.versions.load("critical_unblock_low").version()).isEqualTo(1L);
        assertThat(f.sent).hasSize(1);
        BinaryNode iq = f.sent.get(0);
        assertThat(iq.attr("xmlns")).isEqualTo("w:sync:app:state");
        assertThat(iq.attr("to")).isEqualTo("s.whatsapp.net");
        BinaryNode collection = iq.child("sync").child("collection");
        assertThat(collection.attr("name")).isEqualTo("critical_unblock_low");
        assertThat(collection.attr("version")).isEqualTo("0");
        assertThat(collection.attr("return_snapshot")).isEqualTo("false");
    }

    @Test
    void chatServiceNoTransportOverloadsDelegateToClientAppPatch(@TempDir Path tempDir) throws Exception {
        Fixture f = new Fixture(tempDir, KEY_ID_B64, true, resultIq());
        ChatService chats = f.client.chats();

        chats.sendServerContactPatch("111@s.whatsapp.net", "Jane Doe", "Jane", "jane", null, null).get();
        chats.sendServerLabelEditPatch("42", "Work", 3, null, false).get();
        chats.sendServerPatch(AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net")).get();

        assertThat(f.sent).hasSize(3);
        assertThat(f.sent.stream().map(iq -> iq.child("sync").child("collection").attr("name")))
            .containsExactly("critical_unblock_low", "regular", "critical_unblock_low");
        assertThat(f.versions.load("critical_unblock_low").version()).isEqualTo(2L);
        assertThat(f.versions.load("regular").version()).isEqualTo(1L);
    }

    @Test
    void chatServiceWithoutClientFailsInsteadOfSending() {
        ChatService chats = new ChatService(null);
        assertThatThrownBy(() -> chats.sendServerPatch(
            AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net")).join())
            .hasCauseInstanceOf(IllegalStateException.class);
    }
}
