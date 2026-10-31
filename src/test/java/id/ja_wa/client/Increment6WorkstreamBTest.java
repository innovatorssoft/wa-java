package id.ja_wa.client;

import id.ja_wa.domain.model.AuthCreds;
import id.ja_wa.domain.store.FileAuthStore;
import id.ja_wa.event.MessagesUpdate;
import id.ja_wa.event.WaEventListener;
import id.ja_wa.feature.appstate.AppPatchBuilder;
import id.ja_wa.feature.appstate.AppPatchEncoder;
import id.ja_wa.feature.appstate.AppPatchTransport;
import id.ja_wa.feature.appstate.AppStateKey;
import id.ja_wa.feature.appstate.AppStateProcessor;
import id.ja_wa.feature.appstate.FileAppStateKeyStorage;
import id.ja_wa.feature.appstate.InMemoryAppStateVersionStore;
import id.ja_wa.feature.appstate.LtHashState;
import id.ja_wa.feature.community.CommunityQuery;
import id.ja_wa.feature.messaging.CallLinkQuery;
import id.ja_wa.feature.newsletter.NewsletterProfilePicture;
import id.ja_wa.proto.Wa;
import id.ja_wa.protocol.codec.BinaryNode;
import id.ja_wa.protocol.crypto.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Increment 6, Workstream B: newsletter picture, quick replies via
 * appPatch, call-link transport, and invite-V4 emission. No live
 * server is used anywhere: MEX / IQ / stanza requests go through the
 * JaWaClient test seams, and the call-response correlation is driven
 * through the client's inbound stanza handler directly.
 */
class Increment6WorkstreamBTest {

    private static final byte[] KEY_ID = "mykeyid1234567890".getBytes(StandardCharsets.UTF_8);

    private record FakeIq(List<BinaryNode> sent, Function<BinaryNode, BinaryNode> reply)
        implements AppPatchTransport.IqSender {
        @Override
        public CompletableFuture<BinaryNode> sendIq(BinaryNode iq) {
            sent.add(iq);
            return CompletableFuture.completedFuture(reply.apply(iq));
        }
    }

    private static JaWaClient clientWithSeam(Path tempDir, FakeIq fake) throws Exception {
        JaWaClient client = new JaWaClient(new FileAuthStore(tempDir.resolve("creds.session")));
        client.setIqSenderForTesting(fake);
        return client;
    }

    private static AppStateKey testKey() {
        byte[] keyData = new byte[32];
        for (int i = 0; i < 32; i++) keyData[i] = (byte) i;
        return new AppStateKey(KEY_ID, keyData);
    }

    private static byte[] pngBytes(int width, int height, Color color) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, width, height);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    // ---- Task 1: newsletter picture ----

    @Test
    void profilePictureResizesTo720x720Jpeg() throws Exception {
        byte[] resized = NewsletterProfilePicture.generate(pngBytes(900, 400, Color.RED));
        assertThat(resized[0]).isEqualTo((byte) 0xFF);
        assertThat(resized[1]).isEqualTo((byte) 0xD8);
        BufferedImage img = decode(resized);
        assertThat(img.getWidth()).isEqualTo(720);
        assertThat(img.getHeight()).isEqualTo(720);
    }

    @Test
    void profilePictureUpscalesSmallSquare() throws Exception {
        BufferedImage img = decode(NewsletterProfilePicture.generate(pngBytes(100, 100, Color.BLUE)));
        assertThat(img.getWidth()).isEqualTo(720);
        assertThat(img.getHeight()).isEqualTo(720);
    }

    @Test
    void profilePictureRejectsUndecodableInput() {
        assertThatThrownBy(() -> NewsletterProfilePicture.generate(new byte[]{1, 2, 3}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NewsletterProfilePicture.generate(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newsletterPictureFacadeSendsResizedBase64ViaMex(@TempDir Path tempDir) throws Exception {
        List<BinaryNode> sent = new ArrayList<>();
        JaWaClient client = clientWithSeam(tempDir, new FakeIq(sent, iq ->
            new BinaryNode("iq", Map.of("type", "result"),
                List.of(new BinaryNode("result", Map.of(), "mock-json")))));
        NewsletterService service = new NewsletterService(client);
        String result = service.updateNewsletterPicture("123@newsletter", pngBytes(800, 500, Color.GREEN)).join();
        assertThat(result).isEqualTo("mock-json");
        assertThat(sent).hasSize(1);
        String variables = sent.get(0).child("query").textContent();
        assertThat(variables).contains("\"picture\":\"");
        String b64 = variables.split("\"picture\":\"")[1].split("\"")[0];
        BufferedImage img = decode(Base64.getDecoder().decode(b64));
        assertThat(img.getWidth()).isEqualTo(720);
        assertThat(img.getHeight()).isEqualTo(720);
    }

    @Test
    void newsletterPictureBase64PassthroughSendsCallerBytes(@TempDir Path tempDir) throws Exception {
        List<BinaryNode> sent = new ArrayList<>();
        JaWaClient client = clientWithSeam(tempDir, new FakeIq(sent, iq ->
            new BinaryNode("iq", Map.of("type", "result"),
                List.of(new BinaryNode("result", Map.of(), "ok")))));
        new NewsletterService(client).updateNewsletterPictureBase64("123@newsletter", "aGVsbG8=").join();
        assertThat(sent.get(0).child("query").textContent()).contains("\"picture\":\"aGVsbG8=\"");
    }

    // ---- Task 2: quick replies via appPatch ----

    @Test
    void quickReplyPatchMatchesBaileysShape() {
        AppPatchBuilder.AppPatch patch = AppPatchBuilder.buildAddOrEditQuickReply("/hi", "Hello!", "1700000000");
        assertThat(patch.index()).containsExactly("quick_reply", "1700000000");
        assertThat(patch.type()).isEqualTo("regular");
        assertThat(patch.apiVersion()).isEqualTo(2);
        assertThat(patch.operation()).isEqualTo(AppPatchBuilder.Operation.SET);
        Wa.SyncActionValue.QuickReplyAction action = patch.syncAction().getQuickReplyAction();
        assertThat(action.getShortcut()).isEqualTo("/hi");
        assertThat(action.getMessage()).isEqualTo("Hello!");
        assertThat(action.getCount()).isEqualTo(0);
        assertThat(action.getKeywordsCount()).isEqualTo(0);
        assertThat(action.getDeleted()).isFalse();
    }

    @Test
    void quickReplyTimestampDefaultsToNowSeconds() {
        long before = System.currentTimeMillis() / 1000L;
        AppPatchBuilder.AppPatch patch = AppPatchBuilder.buildAddOrEditQuickReply("/x", "y", null);
        long after = System.currentTimeMillis() / 1000L;
        long ts = Long.parseLong(patch.index().get(1));
        assertThat(ts).isBetween(before, after);
    }

    @Test
    void quickReplyRemoveMarksDeletedWithEmptyFields() {
        AppPatchBuilder.AppPatch patch = AppPatchBuilder.buildRemoveQuickReply("1700000000");
        assertThat(patch.index()).containsExactly("quick_reply", "1700000000");
        // Baileys remove is still OP.SET; deletion is the deleted flag.
        assertThat(patch.operation()).isEqualTo(AppPatchBuilder.Operation.SET);
        assertThat(patch.syncAction().getQuickReplyAction().getDeleted()).isTrue();
        assertThat(patch.syncAction().getQuickReplyAction().getMessage()).isEmpty();
        assertThat(patch.syncAction().getQuickReplyAction().getShortcut()).isEmpty();
        assertThatThrownBy(() -> AppPatchBuilder.buildRemoveQuickReply(" "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void quickReplyPatchRoundTripsThroughEncoder(@TempDir Path dir) {
        AppPatchBuilder.AppPatch patch = AppPatchBuilder.buildAddOrEditQuickReply("/hi", "Hello!", "1700000000");
        AppPatchEncoder.EncodedPatch encoded = AppPatchEncoder.encode(patch, testKey(), LtHashState.fresh());
        FileAppStateKeyStorage storage = new FileAppStateKeyStorage(dir);
        storage.put(testKey());
        AppStateProcessor.Mutation decoded =
            new AppStateProcessor(storage).decode(encoded.patch().getMutations(0), true);
        assertThat(new String(decoded.action().getIndex().toByteArray(), StandardCharsets.UTF_8))
            .isEqualTo("[\"quick_reply\",\"1700000000\"]");
        assertThat(decoded.action().getVersion()).isEqualTo(2);
        assertThat(decoded.action().getValue().getQuickReplyAction().getMessage()).isEqualTo("Hello!");
        assertThat(decoded.action().getValue().getQuickReplyAction().getShortcut()).isEqualTo("/hi");
    }

    @Test
    void chatServiceQuickReplySendsViaClientAppPatch(@TempDir Path tempDir) throws Exception {
        FileAuthStore store = new FileAuthStore(tempDir.resolve("creds.session"));
        JaWaClient client = new JaWaClient(store);
        AuthCreds creds = AuthCreds.generate();
        creds.myAppStateKeyId = Bytes.toBase64(KEY_ID);
        client.setCredsForTesting(creds);
        FileAppStateKeyStorage keys = new FileAppStateKeyStorage(tempDir.resolve("appstate-keys"));
        keys.put(testKey());
        client.setAppStateKeyStorageForTesting(keys);
        client.setAppStateVersionStoreForTesting(new InMemoryAppStateVersionStore());
        List<BinaryNode> sent = new ArrayList<>();
        client.setAppPatchIqSenderForTesting(new FakeIq(sent,
            iq -> new BinaryNode("iq", Map.of("type", "result"), null)));
        ChatService chats = new ChatService(client);
        AppPatchEncoder.EncodedPatch encoded = chats.addOrEditQuickReply("/hi", "Hello!", "1700000000").join();
        assertThat(encoded.patch().getMutationsCount()).isEqualTo(1);
        assertThat(sent).hasSize(1);
        AppPatchEncoder.EncodedPatch removed = chats.removeQuickReply("1700000000").join();
        assertThat(removed.patch().getMutationsCount()).isEqualTo(1);
    }

    // ---- Task 3: call-link transport ----

    @Test
    void callLinkStanzaShapeWithAndWithoutEvent() {
        BinaryNode plain = CallLinkQuery.buildCreate("tag1", "video", null);
        assertThat(plain.tag()).isEqualTo("call");
        assertThat(plain.attr("to")).isEqualTo("@call");
        assertThat(plain.child("link_create").attr("media")).isEqualTo("video");
        assertThat(plain.child("link_create").child("event")).isNull();

        BinaryNode withEvent = CallLinkQuery.buildCreate("tag2", "audio", 1800000000L);
        assertThat(withEvent.child("link_create").child("event").attr("start_time")).isEqualTo("1800000000");
        assertThat(CallLinkQuery.parseToken(new BinaryNode("call", Map.of(),
            List.of(new BinaryNode("link_create", Map.of("token", "tok-1"), null))))).isEqualTo("tok-1");
    }

    @Test
    void createCallLinkFacadeReturnsTokenAndSendsCallStanza(@TempDir Path tempDir) throws Exception {
        List<BinaryNode> sent = new ArrayList<>();
        JaWaClient client = clientWithSeam(tempDir, new FakeIq(sent, iq ->
            new BinaryNode("call", Map.of("id", iq.attr("id")),
                List.of(new BinaryNode("link_create", Map.of("token", "tok-xyz"), null)))));
        String token = client.createCallLink("video", 1800000000L).join();
        assertThat(token).isEqualTo("tok-xyz");
        assertThat(sent.get(0).tag()).isEqualTo("call");
        assertThat(sent.get(0).attr("to")).isEqualTo("@call");
        assertThat(sent.get(0).child("link_create").child("event").attr("start_time")).isEqualTo("1800000000");
        // GroupService delegate uses the same transport.
        assertThat(new GroupService(client).createCallLink("audio", null).join()).isEqualTo("tok-xyz");
    }

    @Test
    void createCallLinkErrorResponseFails(@TempDir Path tempDir) throws Exception {
        JaWaClient client = clientWithSeam(tempDir,
            new FakeIq(new ArrayList<>(), iq -> new BinaryNode("call", Map.of("type", "error"), null)));
        assertThatThrownBy(() -> client.createCallLink("video", null).join())
            .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void inboundCallStanzaCompletesPendingStanzaRequest(@TempDir Path tempDir) throws Exception {
        JaWaClient client = new JaWaClient(new FileAuthStore(tempDir.resolve("creds.session")));
        BinaryNode request = CallLinkQuery.buildCreate("call-req-1", "video", null);
        CompletableFuture<BinaryNode> pending = client.sendStanzaAsync(request);
        assertThat(pending.isDone()).isFalse();
        BinaryNode response = new BinaryNode("call", Map.of("id", "call-req-1"),
            List.of(new BinaryNode("link_create", Map.of("token", "tok-live"), null)));
        assertThat(client.handleStanzaForTesting(response)).isTrue();
        assertThat(pending.isDone()).isTrue();
        assertThat(CallLinkQuery.parseToken(pending.join())).isEqualTo("tok-live");

        // An unmatched <call> stanza is NOT consumed as a response.
        BinaryNode unmatched = new BinaryNode("call", Map.of("id", "other"),
            List.of(new BinaryNode("offer", Map.of(), null)));
        assertThat(client.handleStanzaForTesting(unmatched)).isFalse();
    }

    // ---- Task 4: invite-V4 emission ----

    private static Wa.Message.GroupInviteMessage invite() {
        return Wa.Message.GroupInviteMessage.newBuilder()
            .setGroupJid("999@g.us")
            .setInviteCode("CODE")
            .setInviteExpiration(1700000000L)
            .setGroupName("Test Community")
            .build();
    }

    @Test
    void expireInviteMessageZeroesExpirationAndCodeOnly() {
        Wa.Message.GroupInviteMessage expired = CommunityQuery.expireInviteMessage(invite());
        assertThat(expired.getInviteExpiration()).isEqualTo(0L);
        assertThat(expired.getInviteCode()).isEmpty();
        assertThat(expired.getGroupJid()).isEqualTo("999@g.us");
        assertThat(expired.getGroupName()).isEqualTo("Test Community");
        assertThatThrownBy(() -> CommunityQuery.expireInviteMessage(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptV4EmitsExpiredInviteUpdateOnlyWithFullKey(@TempDir Path tempDir) throws Exception {
        List<BinaryNode> sent = new ArrayList<>();
        JaWaClient client = clientWithSeam(tempDir, new FakeIq(sent, iq ->
            new BinaryNode("iq", Map.of("type", "result", "from", "999@g.us"), null)));
        List<MessagesUpdate> received = new ArrayList<>();
        client.addEventListener(new WaEventListener() {
            @Override
            public void onMessagesUpdate(MessagesUpdate update) {
                received.add(update);
            }
        });
        String from = client.acceptCommunityInviteV4("888@s.whatsapp.net", "msg-1", false, null, invite()).join();
        assertThat(from).isEqualTo("999@g.us");
        assertThat(sent.get(0).child("accept").attr("admin")).isEqualTo("888@s.whatsapp.net");
        assertThat(received).hasSize(1);
        MessagesUpdate.MessageUpdate update = received.get(0).updates().get(0);
        assertThat(update.messageId()).isEqualTo("msg-1");
        assertThat(update.remoteJid()).isEqualTo("888@s.whatsapp.net");
        assertThat(update.groupInviteMessage().getInviteExpiration()).isEqualTo(0L);
        assertThat(update.groupInviteMessage().getInviteCode()).isEmpty();
        assertThat(update.groupInviteMessage().getGroupJid()).isEqualTo("999@g.us");

        // Bare-JID key (no message id): Baileys skips the emission.
        received.clear();
        client.acceptCommunityInviteV4("888@s.whatsapp.net", null, false, null, invite()).join();
        assertThat(received).isEmpty();

        // GroupService delegate performs the same accept + emission.
        received.clear();
        String viaService = new GroupService(client)
            .acceptCommunityInviteV4("888@s.whatsapp.net", "msg-2", false, null, invite()).join();
        assertThat(viaService).isEqualTo("999@g.us");
        assertThat(received).hasSize(1);
        assertThat(received.get(0).updates().get(0).messageId()).isEqualTo("msg-2");
    }
}
