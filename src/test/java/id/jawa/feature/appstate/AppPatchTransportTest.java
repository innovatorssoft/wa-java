package id.jawa.feature.appstate;

import id.jawa.proto.Wa;
import id.jawa.protocol.codec.BinaryNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppPatchTransportTest {

    private static final byte[] KEY_ID = "mykeyid1234567890".getBytes(StandardCharsets.UTF_8);

    private static AppStateKey testKey() {
        byte[] keyData = new byte[32];
        for (int i = 0; i < 32; i++) keyData[i] = (byte) (31 - i);
        return new AppStateKey(KEY_ID, keyData);
    }

    private record FakeIq(List<BinaryNode> sent, BinaryNode reply, Throwable failure) implements AppPatchTransport.IqSender {
        @Override
        public CompletableFuture<BinaryNode> sendIq(BinaryNode iq) {
            sent.add(iq);
            if (failure != null) return CompletableFuture.failedFuture(failure);
            return CompletableFuture.completedFuture(reply);
        }
    }

    private static BinaryNode resultIq() {
        return new BinaryNode("iq", Map.of("type", "result"), null);
    }

    @Test
    void sendResyncsEncodesSendsAndPersistsVersion() throws Exception {
        List<String> resynced = new ArrayList<>();
        List<BinaryNode> sent = new ArrayList<>();
        InMemoryAppStateVersionStore versions = new InMemoryAppStateVersionStore();
        AtomicInteger ids = new AtomicInteger();
        AppPatchTransport transport = new AppPatchTransport(
            id -> testKey(),
            name -> { resynced.add(name); return CompletableFuture.completedFuture(null); },
            versions,
            new FakeIq(sent, resultIq(), null),
            () -> "iq" + ids.incrementAndGet());

        AppPatchBuilder.AppPatch patch = AppPatchBuilder.buildAddOrEditContact(
            "111@s.whatsapp.net", "Jane Doe", null, null, null, null);
        AppPatchEncoder.EncodedPatch result = transport.send(patch, KEY_ID).get();

        assertThat(resynced).containsExactly("critical_unblock_low");
        assertThat(sent).hasSize(1);
        BinaryNode iq = sent.get(0);
        assertThat(iq.tag()).isEqualTo("iq");
        assertThat(iq.attr("type")).isEqualTo("set");
        assertThat(iq.attr("xmlns")).isEqualTo("w:sync:app:state");
        assertThat(iq.attr("to")).isEqualTo("s.whatsapp.net");
        BinaryNode collection = iq.child("sync").child("collection");
        assertThat(collection.attr("name")).isEqualTo("critical_unblock_low");
        // Fresh state -> new version 1 -> collection version is newVersion - 1 = 0.
        assertThat(collection.attr("version")).isEqualTo("0");
        assertThat(collection.attr("return_snapshot")).isEqualTo("false");
        Wa.SyncdPatch wire = Wa.SyncdPatch.parseFrom(collection.child("patch").bytesContent());
        assertThat(wire).isEqualTo(result.patch());
        assertThat(versions.load("critical_unblock_low").version()).isEqualTo(1L);

        // Second send on the same store: collection version advances to 1.
        transport.send(AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net"), KEY_ID).get();
        BinaryNode secondCollection = sent.get(1).child("sync").child("collection");
        assertThat(secondCollection.attr("version")).isEqualTo("1");
        assertThat(versions.load("critical_unblock_low").version()).isEqualTo(2L);
    }

    @Test
    void missingKeyIdFailsBeforeResync() {
        List<String> resynced = new ArrayList<>();
        AppPatchTransport transport = new AppPatchTransport(
            id -> testKey(),
            name -> { resynced.add(name); return CompletableFuture.completedFuture(null); },
            new InMemoryAppStateVersionStore(),
            iq -> CompletableFuture.completedFuture(resultIq()),
            () -> "iq1");
        assertThatThrownBy(() -> transport
            .send(AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net"), null).join())
            .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(resynced).isEmpty();
    }

    @Test
    void missingKeyFailsWithoutPersisting() {
        InMemoryAppStateVersionStore versions = new InMemoryAppStateVersionStore();
        AppPatchTransport transport = new AppPatchTransport(
            id -> null,
            name -> CompletableFuture.completedFuture(null),
            versions,
            iq -> CompletableFuture.completedFuture(resultIq()),
            () -> "iq1");
        assertThatThrownBy(() -> transport
            .send(AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net"), KEY_ID).join())
            .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(versions.load("critical_unblock_low")).isNull();
    }

    @Test
    void errorIqAndSendFailureDoNotPersist() {
        InMemoryAppStateVersionStore versions = new InMemoryAppStateVersionStore();
        BinaryNode errorIq = new BinaryNode("iq", Map.of("type", "error"), null);
        AppPatchTransport errorTransport = new AppPatchTransport(
            id -> testKey(),
            name -> CompletableFuture.completedFuture(null),
            versions,
            iq -> CompletableFuture.completedFuture(errorIq),
            () -> "iq1");
        assertThatThrownBy(() -> errorTransport
            .send(AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net"), KEY_ID).join())
            .hasCauseInstanceOf(IllegalStateException.class)
            .hasMessageContaining("rejected");
        assertThat(versions.load("critical_unblock_low")).isNull();

        AppPatchTransport failingTransport = new AppPatchTransport(
            id -> testKey(),
            name -> CompletableFuture.completedFuture(null),
            versions,
            iq -> CompletableFuture.failedFuture(new RuntimeException("socket down")),
            () -> "iq1");
        assertThatThrownBy(() -> failingTransport
            .send(AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net"), KEY_ID).join())
            .hasCauseInstanceOf(RuntimeException.class);
        assertThat(versions.load("critical_unblock_low")).isNull();
    }

    @Test
    void buildAppPatchIqRejectsBadInput() {
        AppPatchEncoder.EncodedPatch encoded = AppPatchEncoder.encode(
            AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net"), testKey(), LtHashState.fresh());
        assertThatThrownBy(() -> AppPatchTransport.buildAppPatchIq(" ",
            AppPatchBuilder.buildRemoveContact("111@s.whatsapp.net"), encoded))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AppPatchTransport.buildAppPatchIq("iq1", null, encoded))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
