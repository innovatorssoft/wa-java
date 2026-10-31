package id.ja_wa.client;

import id.ja_wa.domain.model.AuthCreds;
import id.ja_wa.domain.store.FileAuthStore;
import id.ja_wa.event.ConnectionUpdate;
import id.ja_wa.event.ConnectionUpdateWaiter;
import id.ja_wa.event.MessageUpsert;
import id.ja_wa.event.WaEventBus;
import id.ja_wa.event.WaEventListener;
import id.ja_wa.feature.appstate.AppPatchBuilder;
import id.ja_wa.feature.appstate.AppPatchTransport;
import id.ja_wa.feature.appstate.AppStateKey;
import id.ja_wa.feature.appstate.FileAppStateKeyStorage;
import id.ja_wa.feature.appstate.InMemoryAppStateVersionStore;
import id.ja_wa.feature.messaging.MessageReceiver;
import id.ja_wa.feature.messaging.MessageSender;
import id.ja_wa.feature.messaging.PeerDataOperations;
import id.ja_wa.feature.messaging.UsyncQuery;
import id.ja_wa.feature.privacy.PrivacyQuery;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Increment 7: privacy IQs, push-name app-state patch, community V4
 * revoke facade, cleanDirtyBits, fetchDisappearingDuration, and the
 * connection-update waiter (plus the peer-framing builder and the
 * PDO-response receiver hook). No live server is used anywhere:
 * IQ requests go through the JaWaClient test seams, appPatch goes
 * through the Increment 5 fake IQ sender, and the waiter is driven
 * through a bare WaEventBus. All timeouts are short (tens of
 * milliseconds) so no test can block indefinitely.
 */
class Increment7Test {

    private record FakeIq(List<BinaryNode> sent, Function<BinaryNode, BinaryNode> reply)
        implements AppPatchTransport.IqSender {
        @Override
        public CompletableFuture<BinaryNode> sendIq(BinaryNode iq) {
            sent.add(iq);
            return CompletableFuture.completedFuture(reply.apply(iq));
        }
    }

    private static BinaryNode resultIq() {
        return new BinaryNode("iq", Map.of("type", "result"), null);
    }

    private static JaWaClient clientWithSeam(Path tempDir, FakeIq fake) throws Exception {
        JaWaClient client = new JaWaClient(new FileAuthStore(tempDir.resolve("creds.session")));
        client.setIqSenderForTesting(fake);
        return client;
    }

    // ---- Task 1: privacy IQs ----

    @Test
    void privacyFetchIqShapeAndCategoryMapParsing() {
        BinaryNode iq = PrivacyQuery.buildFetch("iq-1");
        assertThat(iq.tag()).isEqualTo("iq");
        assertThat(iq.attr("xmlns")).isEqualTo("privacy");
        assertThat(iq.attr("type")).isEqualTo("get");
        assertThat(iq.attr("to")).isEqualTo("s.whatsapp.net");
        assertThat(iq.child("privacy")).isNotNull();

        BinaryNode response = new BinaryNode("iq", Map.of("type", "result"), List.of(
            new BinaryNode("privacy", Map.of(), List.of(
                new BinaryNode("category", Map.of("name", "last", "value", "contacts"), null),
                new BinaryNode("category", Map.of("name", "groupadd", "value", "all"), null),
                new BinaryNode("category", Map.of("name", "readreceipts", "value", "none"), null),
                new BinaryNode("category", Map.of("value", "all"), null)))));
        Map<String, String> map = PrivacyQuery.parseFetch(response);
        assertThat(map).containsEntry("last", "contacts")
            .containsEntry("groupadd", "all")
            .containsEntry("readreceipts", "none")
            .doesNotContainKey(null);
        assertThat(PrivacyQuery.parseFetch(null)).isEmpty();
        assertThat(PrivacyQuery.parseFetch(resultIq())).isEmpty();
    }

    @Test
    void privacyUpdateIqShapesForEveryCategory(@TempDir Path tempDir) throws Exception {
        List<BinaryNode> sent = new ArrayList<>();
        JaWaClient client = clientWithSeam(tempDir, new FakeIq(sent, iq -> resultIq()));
        PrivacyService privacy = client.privacy();

        privacy.updateMessagesPrivacy("contacts").join();
        privacy.updateCallPrivacy("none").join();
        privacy.updateLastSeenPrivacy("all").join();
        privacy.updateOnlinePrivacy("contacts").join();
        privacy.updateProfilePicturePrivacy("contacts").join();
        privacy.updateStatusPrivacy("contact_blacklist").join();
        privacy.updateReadReceiptsPrivacy("none").join();
        privacy.updateGroupsAddPrivacy("contacts").join();

        assertThat(sent).hasSize(8);
        String[] categories = {"messages", "calladd", "last", "online",
            "profile", "status", "readreceipts", "groupadd"};
        String[] values = {"contacts", "none", "all", "contacts",
            "contacts", "contact_blacklist", "none", "contacts"};
        for (int i = 0; i < sent.size(); i++) {
            BinaryNode iq = sent.get(i);
            assertThat(iq.attr("xmlns")).isEqualTo("privacy");
            assertThat(iq.attr("type")).isEqualTo("set");
            assertThat(iq.attr("to")).isEqualTo("s.whatsapp.net");
            BinaryNode category = iq.child("privacy").child("category");
            assertThat(category.attr("name")).isEqualTo(categories[i]);
            assertThat(category.attr("value")).isEqualTo(values[i]);
        }
    }

    @Test
    void privacyFetchCachesUntilForced(@TempDir Path tempDir) throws Exception {
        List<BinaryNode> sent = new ArrayList<>();
        BinaryNode response = new BinaryNode("iq", Map.of("type", "result"), List.of(
            new BinaryNode("privacy", Map.of(), List.of(
                new BinaryNode("category", Map.of("name", "last", "value", "all"), null)))));
        JaWaClient client = clientWithSeam(tempDir, new FakeIq(sent, iq -> response));

        assertThat(client.fetchPrivacySettings().join().lastSeen()).isEqualTo("all");
        assertThat(sent).hasSize(1);
        // Cached: no second round trip.
        assertThat(client.fetchPrivacySettings().join().lastSeen()).isEqualTo("all");
        assertThat(sent).hasSize(1);
        // Forced: refetches.
        assertThat(client.fetchPrivacySettings(true).join().lastSeen()).isEqualTo("all");
        assertThat(sent).hasSize(2);
        // The pre-existing uncached getter still fetches every time.
        client.getPrivacySettings().join();
        assertThat(sent).hasSize(3);
    }

    @Test
    void defaultDisappearingModeIqShape(@TempDir Path tempDir) throws Exception {
        List<BinaryNode> sent = new ArrayList<>();
        JaWaClient client = clientWithSeam(tempDir, new FakeIq(sent, iq -> resultIq()));
        client.privacy().updateDefaultDisappearingMode(86400).join();
        client.setDefaultDisappearingTimer(0).join();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).attr("xmlns")).isEqualTo("disappearing_mode");
        assertThat(sent.get(0).attr("type")).isEqualTo("set");
        assertThat(sent.get(0).child("disappearing_mode").attr("duration")).isEqualTo("86400");
        assertThat(sent.get(1).child("disappearing_mode").attr("duration")).isEqualTo("0");
        assertThat(PrivacyQuery.buildUpdateDefaultDisappearingMode("x", 604800)
            .child("disappearing_mode").attr("duration")).isEqualTo("604800");
    }

    // ---- Task 2: push-name app-state patch + link-preview patch ----

    @Test
    void pushNamePatchShape() {
        AppPatchBuilder.AppPatch patch = AppPatchBuilder.buildPushNameSetting("Javed");
        assertThat(patch.index()).containsExactly("setting_pushName");
        assertThat(patch.type()).isEqualTo("critical_block");
        assertThat(patch.apiVersion()).isEqualTo(1);
        assertThat(patch.operation()).isEqualTo(AppPatchBuilder.Operation.SET);
        assertThat(patch.syncAction().getPushNameSetting().getName()).isEqualTo("Javed");
        assertThatThrownBy(() -> AppPatchBuilder.buildPushNameSetting(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ChatService(null).buildServerPushNamePatch("Javed").index())
            .containsExactly("setting_pushName");
    }

    @Test
    void disableLinkPreviewsPatchShape() {
        AppPatchBuilder.AppPatch patch = AppPatchBuilder.buildDisableLinkPreviews(true);
        assertThat(patch.index()).containsExactly("setting_disableLinkPreviews");
        assertThat(patch.type()).isEqualTo("regular");
        assertThat(patch.apiVersion()).isEqualTo(8);
        assertThat(patch.syncAction().getPrivacySettingDisableLinkPreviewsAction().getIsPreviewsDisabled()).isTrue();
        assertThat(AppPatchBuilder.buildDisableLinkPreviews(false)
            .syncAction().getPrivacySettingDisableLinkPreviewsAction().getIsPreviewsDisabled()).isFalse();
    }

    @Test
    void updateProfileNameGoesThroughAppPatch(@TempDir Path tempDir) throws Exception {
        byte[] keyId = "mykeyid1234567890".getBytes(StandardCharsets.UTF_8);
        List<BinaryNode> sent = new ArrayList<>();
        FileAuthStore store = new FileAuthStore(tempDir.resolve("creds.session"));
        JaWaClient client = new JaWaClient(store);
        AuthCreds creds = AuthCreds.generate();
        creds.myAppStateKeyId = Bytes.toBase64(keyId);
        client.setCredsForTesting(creds);
        byte[] keyData = new byte[32];
        for (int i = 0; i < 32; i++) keyData[i] = (byte) i;
        FileAppStateKeyStorage keys = new FileAppStateKeyStorage(tempDir.resolve("appstate-keys"));
        keys.put(new AppStateKey(keyId, keyData));
        client.setAppStateKeyStorageForTesting(keys);
        client.setAppStateVersionStoreForTesting(new InMemoryAppStateVersionStore());
        client.setAppPatchIqSenderForTesting(new FakeIq(sent, iq -> resultIq()));

        client.updateProfileName("Javed").get();
        client.chats().updateProfileName("Javed").get();
        client.chats().updateDisableLinkPreviewsPrivacy(true).get();
        assertThat(sent).hasSize(3);
        // The appPatch transport names the collection by patch type:
        // critical_block for push-name, regular for link previews —
        // proving these go through appPatch, not the presence path.
        assertThat(sent.stream().map(iq -> iq.child("sync").child("collection").attr("name")))
            .containsExactly("critical_block", "critical_block", "regular");
        assertThatThrownBy(() -> new ChatService(null).updateProfileName("Javed").join())
            .hasCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> client.updateProfileName(null).join())
            .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    // ---- Task 3: community V4 revoke facade ----

    @Test
    void revokeCommunityInviteV4ShapeAndFacade(@TempDir Path tempDir) throws Exception {
        List<BinaryNode> sent = new ArrayList<>();
        JaWaClient client = clientWithSeam(tempDir,
            new FakeIq(sent, iq -> new BinaryNode("iq", Map.of("type", "result"), null)));
        assertThat(client.revokeCommunityInviteV4("123@g.us", "999@s.whatsapp.net").join()).isTrue();
        assertThat(new GroupService(client)
            .revokeCommunityInviteV4("123@g.us", "999@s.whatsapp.net").join()).isTrue();
        assertThat(sent).hasSize(2);
        BinaryNode iq = sent.get(0);
        assertThat(iq.attr("xmlns")).isEqualTo("w:g2");
        assertThat(iq.attr("type")).isEqualTo("set");
        assertThat(iq.attr("to")).isEqualTo("123@g.us");
        assertThat(iq.child("revoke").child("participant").attr("jid")).isEqualTo("999@s.whatsapp.net");

        JaWaClient nullClient = clientWithSeam(tempDir, new FakeIq(new ArrayList<>(), req -> null));
        assertThat(nullClient.revokeCommunityInviteV4("123@g.us", "999@s.whatsapp.net").join()).isFalse();
        assertThatThrownBy(() -> client.revokeCommunityInviteV4(" ", "999@s.whatsapp.net").join())
            .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    // ---- Task 4: cleanDirtyBits ----

    @Test
    void cleanDirtyBitsStanzaShape(@TempDir Path tempDir) throws Exception {
        List<BinaryNode> sent = new ArrayList<>();
        JaWaClient client = clientWithSeam(tempDir, new FakeIq(sent, iq -> resultIq()));
        client.cleanDirtyBits("account_sync", 1700000000L).join();
        client.cleanDirtyBits("account_sync", null).join();
        client.cleanDirtyBits("account_sync", 0L).join();
        client.chats().cleanDirtyBits("communities", 42L).join();
        assertThat(sent).hasSize(4);
        BinaryNode iq = sent.get(0);
        assertThat(iq.attr("xmlns")).isEqualTo("urn:xmpp:whatsapp:dirty");
        assertThat(iq.attr("type")).isEqualTo("set");
        assertThat(iq.attr("to")).isEqualTo("s.whatsapp.net");
        assertThat(iq.attr("id")).isNotBlank();
        assertThat(iq.child("clean").attr("type")).isEqualTo("account_sync");
        assertThat(iq.child("clean").attr("timestamp")).isEqualTo("1700000000");
        // Null and 0 omit the timestamp, mirroring Baileys' JS truthiness check.
        assertThat(sent.get(1).child("clean").attr("timestamp")).isNull();
        assertThat(sent.get(2).child("clean").attr("timestamp")).isNull();
        assertThat(sent.get(3).child("clean").attr("type")).isEqualTo("communities");
        assertThatThrownBy(() -> client.cleanDirtyBits(" ", 1L).join())
            .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    // ---- Task 5: fetchDisappearingDuration ----

    private static BinaryNode disappearingResultIq() {
        BinaryNode user1 = new BinaryNode("user", Map.of("jid", "111@s.whatsapp.net"), List.of(
            new BinaryNode("disappearing_mode", Map.of("duration", "86400", "t", "1700000000"), null)));
        BinaryNode user2 = new BinaryNode("user", Map.of("jid", "222@s.whatsapp.net"), List.of(
            new BinaryNode("disappearing_mode", Map.of("duration", "0"), null)));
        BinaryNode user3 = new BinaryNode("user", Map.of("jid", "333@s.whatsapp.net"), null);
        BinaryNode usync = new BinaryNode("usync", Map.of(), List.of(
            new BinaryNode("list", Map.of(), List.of(user1, user2, user3))));
        return new BinaryNode("iq", Map.of("type", "result"), List.of(usync));
    }

    @Test
    void disappearingModeQueryShapeAndParse() {
        UsyncQuery.GenericQuery query =
            UsyncQuery.buildDisappearingModeGenericQuery(
                java.util.Arrays.asList("111@s.whatsapp.net", " ", null));
        assertThat(query.queryElements()).hasSize(1);
        assertThat(query.queryElements().get(0).tag()).isEqualTo("disappearing_mode");
        assertThat(query.userElements()).hasSize(1);
        assertThat(query.userElements().get(0).attr("jid")).isEqualTo("111@s.whatsapp.net");
        assertThat(query.userElements().get(0).childrenList()).isEmpty();

        Map<String, UsyncQuery.DisappearingDuration> parsed = UsyncQuery.parseDisappearingModeResult(
            UsyncQuery.parseGenericResult(disappearingResultIq()));
        assertThat(parsed).containsKeys("111@s.whatsapp.net", "222@s.whatsapp.net");
        assertThat(parsed.get("111@s.whatsapp.net").durationSeconds()).isEqualTo(86400L);
        assertThat(parsed.get("111@s.whatsapp.net").setAtEpochMs()).isEqualTo(1700000000L * 1000L);
        assertThat(parsed.get("222@s.whatsapp.net").setAtEpochMs()).isEqualTo(0L);
        // No disappearing_mode child -> omitted, as documented.
        assertThat(parsed).doesNotContainKey("333@s.whatsapp.net");
        assertThat(UsyncQuery.parseDisappearingModeResult(null)).isEmpty();
        assertThat(UsyncQuery.parseDisappearingModeResult(
            UsyncQuery.parseGenericResult(resultIq()))).isEmpty();
    }

    @Test
    void fetchDisappearingDurationViaClient(@TempDir Path tempDir) throws Exception {
        List<BinaryNode> sent = new ArrayList<>();
        JaWaClient client = clientWithSeam(tempDir, new FakeIq(sent, iq -> disappearingResultIq()));
        Map<String, UsyncQuery.DisappearingDuration> result =
            client.fetchDisappearingDuration(List.of("111@s.whatsapp.net", "222@s.whatsapp.net")).join();
        assertThat(result.get("111@s.whatsapp.net").durationSeconds()).isEqualTo(86400L);
        assertThat(result.get("222@s.whatsapp.net").durationSeconds()).isEqualTo(0L);
        assertThat(sent).hasSize(1);
        BinaryNode iq = sent.get(0);
        assertThat(iq.attr("xmlns")).isEqualTo("usync");
        assertThat(iq.child("usync").child("query").child("disappearing_mode")).isNotNull();

        JaWaClient emptyClient = clientWithSeam(tempDir, new FakeIq(new ArrayList<>(), req -> resultIq()));
        assertThat(emptyClient.fetchDisappearingDuration("111@s.whatsapp.net").join()).isEmpty();
    }

    // ---- Task 6: connection-update waiter ----

    @Test
    void waiterMatchesPredicateAndUnsubscribes() {
        WaEventBus bus = new WaEventBus();
        CompletableFuture<ConnectionUpdate> future = ConnectionUpdateWaiter.waitFor(
            bus, u -> u.connection() == ConnectionUpdate.ConnectionState.OPEN, 5000);
        assertThat(bus.listenerCount()).isEqualTo(1);
        bus.dispatch(id.ja_wa.event.BaileysEventType.CONNECTION_UPDATE, ConnectionUpdate.connecting());
        assertThat(future.isDone()).isFalse();
        bus.dispatch(id.ja_wa.event.BaileysEventType.CONNECTION_UPDATE, ConnectionUpdate.open());
        assertThat(future.join().connection()).isEqualTo(ConnectionUpdate.ConnectionState.OPEN);
        assertThat(bus.listenerCount()).isEqualTo(0);
    }

    @Test
    void waiterTimesOutWithNullAndUnsubscribes() {
        WaEventBus bus = new WaEventBus();
        CompletableFuture<ConnectionUpdate> future = ConnectionUpdateWaiter.waitFor(
            bus, u -> u.connection() == ConnectionUpdate.ConnectionState.OPEN, 50);
        bus.dispatch(id.ja_wa.event.BaileysEventType.CONNECTION_UPDATE, ConnectionUpdate.connecting());
        assertThat(future.join()).isNull();
        assertThat(bus.listenerCount()).isEqualTo(0);
    }

    @Test
    void waiterTreatsThrowingPredicateAsNoMatch() {
        WaEventBus bus = new WaEventBus();
        CompletableFuture<ConnectionUpdate> future = ConnectionUpdateWaiter.waitFor(
            bus, u -> { throw new RuntimeException("boom"); }, 50);
        bus.dispatch(id.ja_wa.event.BaileysEventType.CONNECTION_UPDATE, ConnectionUpdate.open());
        assertThat(future.join()).isNull();
        assertThat(bus.listenerCount()).isEqualTo(0);
    }

    @Test
    void waiterCloseUpdateCanMatchAndClientMethodWorks(@TempDir Path tempDir) throws Exception {
        WaEventBus bus = new WaEventBus();
        CompletableFuture<ConnectionUpdate> closeFuture = ConnectionUpdateWaiter.waitFor(
            bus, u -> u.connection() == ConnectionUpdate.ConnectionState.CLOSE, 5000);
        bus.dispatch(id.ja_wa.event.BaileysEventType.CONNECTION_UPDATE, ConnectionUpdate.close("gone"));
        assertThat(closeFuture.join().lastDisconnectError()).isEqualTo("gone");

        JaWaClient client = clientWithSeam(tempDir, new FakeIq(new ArrayList<>(), iq -> resultIq()));
        CompletableFuture<ConnectionUpdate> future = client.waitForConnectionUpdate(
            u -> u.qr() != null, 5000);
        new Thread(() -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
            }
            client.eventBus().dispatch(id.ja_wa.event.BaileysEventType.CONNECTION_UPDATE,
                ConnectionUpdate.qr("qr-payload"));
        }).start();
        assertThat(future.get(5, TimeUnit.SECONDS).qr()).isEqualTo("qr-payload");
        assertThat(client.eventBus().listenerCount()).isEqualTo(0);
        assertThatThrownBy(() -> ConnectionUpdateWaiter.waitFor(bus, u -> true, -1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectionUpdateWaiter.waitFor(null, u -> true, 10))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Stretch 7: peer framing builder ----

    @Test
    void peerFrameReframesFirstEncBare() {
        byte[] encBytes = {1, 2, 3};
        byte[] identityBytes = {9, 9};
        BinaryNode enc = new BinaryNode("enc", Map.of("v", "2", "type", "msg"), encBytes);
        BinaryNode to = new BinaryNode("to", Map.of("jid", "111@s.whatsapp.net:0"), List.of(enc));
        BinaryNode stanza = new BinaryNode("message",
            Map.of("to", "111@s.whatsapp.net", "id", "MSG1", "type", "text"),
            List.of(
                new BinaryNode("participants", Map.of(), List.of(to)),
                new BinaryNode("device-identity", Map.of(), identityBytes),
                new BinaryNode("meta", Map.of("appdata", "default"), null)));
        BinaryNode reframed = MessageSender.peerFrame(
            new MessageSender.Result(stanza, Map.of()));
        assertThat(reframed.attr("to")).isEqualTo("111@s.whatsapp.net");
        assertThat(reframed.attr("id")).isEqualTo("MSG1");
        assertThat(reframed.child("participants")).isNull();
        List<BinaryNode> children = reframed.childrenList();
        assertThat(children.get(0).tag()).isEqualTo("enc");
        assertThat(children.get(0).bytesContent()).isEqualTo(encBytes);
        assertThat(children.get(1).tag()).isEqualTo("device-identity");
        assertThat(children.get(1).bytesContent()).isEqualTo(identityBytes);
        assertThat(children.get(2).tag()).isEqualTo("meta");
        assertThatThrownBy(() -> MessageSender.peerFrame(
            new MessageSender.Result(new BinaryNode("message", Map.of(), List.of()), Map.of())))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Stretch 8: PDO-response receiver hook ----

    @Test
    void pdoResponseResolvesClaimAndDispatchesUpsert(@TempDir Path tempDir) throws Exception {
        JaWaClient client = clientWithSeam(tempDir, new FakeIq(new ArrayList<>(), iq -> resultIq()));
        List<MessageUpsert> upserts = new ArrayList<>();
        client.addEventListener(new WaEventListener() {
            @Override
            public void onMessageUpsert(MessageUpsert upsert) {
                upserts.add(upsert);
            }
        });

        // Stored original data, as requestPlaceholderResend would store it.
        MessageReceiver.Decoded stored = new MessageReceiver.Decoded(
            "222@s.whatsapp.net", null, "MSG9", "msg",
            Wa.Message.newBuilder().setConversation("old").build(),
            "old", null, 111L);
        assertThat(client.placeholderResendManagerForTesting().tryClaim("MSG9", stored)).isTrue();

        Wa.WebMessageInfo reply = Wa.WebMessageInfo.newBuilder()
            .setKey(Wa.MessageKey.newBuilder()
                .setId("MSG9").setRemoteJid("222@s.whatsapp.net").setFromMe(false).build())
            .setMessage(Wa.Message.newBuilder().setConversation("hello again").build())
            .setMessageTimestamp(1700000001L)
            .build();
        Wa.Message.PeerDataOperationRequestResponseMessage response =
            Wa.Message.PeerDataOperationRequestResponseMessage.newBuilder()
                .setStanzaId("PDO1")
                .addPeerDataOperationResult(Wa.Message.PeerDataOperationRequestResponseMessage.PeerDataOperationResult
                    .newBuilder()
                    .setPlaceholderMessageResendResponse(Wa.Message.PeerDataOperationRequestResponseMessage
                        .PeerDataOperationResult.PlaceholderMessageResendResponse.newBuilder()
                        .setWebMessageInfoBytes(reply.toByteString()).build())
                    .build())
                // A result with no bytes, and a non-placeholder result, are ignored.
                .addPeerDataOperationResult(Wa.Message.PeerDataOperationRequestResponseMessage.PeerDataOperationResult
                    .newBuilder().build())
                .build();

        List<MessageReceiver.Decoded> dispatched = client.dispatchPlaceholderResendResponses(response);
        assertThat(dispatched).hasSize(1);
        MessageReceiver.Decoded decoded = dispatched.get(0);
        // Baileys' merge: stored metadata preserved, content + timestamp from the reply.
        assertThat(decoded.senderJid()).isEqualTo("222@s.whatsapp.net");
        assertThat(decoded.msgId()).isEqualTo("MSG9");
        assertThat(decoded.message().getConversation()).isEqualTo("hello again");
        assertThat(decoded.timestamp()).isEqualTo(1700000001L);
        assertThat(client.placeholderResendManagerForTesting().isPending("MSG9")).isFalse();
        assertThat(upserts).hasSize(1);
        assertThat(upserts.get(0).type()).isEqualTo("notify");
        assertThat(upserts.get(0).messages()).hasSize(1);

        // Pure helper: bad bytes are skipped, empty responses yield nothing.
        PeerDataOperations.PlaceholderResendManager manager =
            new PeerDataOperations.PlaceholderResendManager();
        Wa.Message.PeerDataOperationRequestResponseMessage bad =
            Wa.Message.PeerDataOperationRequestResponseMessage.newBuilder()
                .addPeerDataOperationResult(Wa.Message.PeerDataOperationRequestResponseMessage.PeerDataOperationResult
                    .newBuilder()
                    .setPlaceholderMessageResendResponse(Wa.Message.PeerDataOperationRequestResponseMessage
                        .PeerDataOperationResult.PlaceholderMessageResendResponse.newBuilder()
                        .setWebMessageInfoBytes(com.google.protobuf.ByteString.copyFrom(new byte[]{1, 2, 3}))
                        .build())
                    .build())
                .build();
        assertThat(PeerDataOperations.resolvePlaceholderResendResponses(bad, manager)).isEmpty();
        assertThat(PeerDataOperations.resolvePlaceholderResendResponses(null, manager)).isEmpty();
    }
}
