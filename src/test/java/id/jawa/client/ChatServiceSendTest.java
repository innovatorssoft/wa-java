package id.jawa.client;

import id.jawa.feature.appstate.AppPatchBuilder;
import id.jawa.feature.appstate.AppPatchEncoder;
import id.jawa.feature.appstate.AppPatchTransport;
import id.jawa.feature.appstate.AppStateKey;
import id.jawa.feature.appstate.InMemoryAppStateVersionStore;
import id.jawa.protocol.codec.BinaryNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ChatService sendServer* facades over a fake transport. The client
 * itself is null here on purpose: the send path touches only the
 * supplied transport, never the client, so these tests prove the
 * build-then-send wiring without a socket.
 */
class ChatServiceSendTest {

    private static final byte[] KEY_ID = "mykeyid1234567890".getBytes(StandardCharsets.UTF_8);

    private static AppStateKey testKey() {
        byte[] keyData = new byte[32];
        for (int i = 0; i < 32; i++) keyData[i] = (byte) i;
        return new AppStateKey(KEY_ID, keyData);
    }

    private static AppPatchTransport fakeTransport(List<BinaryNode> sent,
                                                   InMemoryAppStateVersionStore versions) {
        return new AppPatchTransport(
            id -> testKey(),
            name -> CompletableFuture.completedFuture(null),
            versions,
            iq -> { sent.add(iq); return CompletableFuture.completedFuture(
                new BinaryNode("iq", Map.of("type", "result"), null)); },
            () -> "iq1");
    }

    @Test
    void sendServerContactPatchBuildsAndSends() throws Exception {
        List<BinaryNode> sent = new ArrayList<>();
        InMemoryAppStateVersionStore versions = new InMemoryAppStateVersionStore();
        ChatService chats = new ChatService(null);

        AppPatchEncoder.EncodedPatch result = chats.sendServerContactPatch(
            fakeTransport(sent, versions), KEY_ID,
            "111@s.whatsapp.net", "Jane Doe", "Jane", "jane", null, null).get();

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).child("sync").child("collection").attr("name"))
            .isEqualTo("critical_unblock_low");
        assertThat(result.state().version()).isEqualTo(1L);
        assertThat(versions.load("critical_unblock_low").version()).isEqualTo(1L);
        // buildServer* stays pure: the builder alone sends nothing.
        AppPatchBuilder.AppPatch built = chats.buildServerContactPatch(
            "111@s.whatsapp.net", "Jane Doe", "Jane", "jane", null, null);
        assertThat(sent).hasSize(1);
        assertThat(built.type()).isEqualTo("critical_unblock_low");
    }

    @Test
    void sendServerLabelPatchesSendThroughTransport() throws Exception {
        List<BinaryNode> sent = new ArrayList<>();
        ChatService chats = new ChatService(null);
        AppPatchTransport transport = fakeTransport(sent, new InMemoryAppStateVersionStore());

        chats.sendServerLabelEditPatch(transport, KEY_ID, "42", "Work", 3, null, false).get();
        chats.sendServerChatLabelPatch(transport, KEY_ID, "111@s.whatsapp.net", "42", true).get();
        chats.sendServerMessageLabelPatch(transport, KEY_ID, "111@s.whatsapp.net", "m1", "42", false).get();
        chats.sendServerLabelDeletePatch(transport, KEY_ID, "42").get();
        chats.sendServerRemoveContactPatch(transport, KEY_ID, "111@s.whatsapp.net").get();

        assertThat(sent).hasSize(5);
        assertThat(sent.stream()
            .map(iq -> iq.child("sync").child("collection").attr("name")))
            .containsExactly("regular", "regular", "regular", "regular", "critical_unblock_low");
    }

    @Test
    void nullTransportFailsInsteadOfSilentlyDropping() {
        ChatService chats = new ChatService(null);
        assertThatThrownBy(() -> chats.sendServerPatch(null, KEY_ID,
            AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net")).join())
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no appPatch transport");
    }
}
