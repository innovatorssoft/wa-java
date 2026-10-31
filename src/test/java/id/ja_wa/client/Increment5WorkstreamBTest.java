package id.ja_wa.client;

import id.ja_wa.domain.store.FileAuthStore;
import id.ja_wa.event.ContactsUpsert;
import id.ja_wa.feature.community.CommunityQuery;
import id.ja_wa.feature.media.MediaUploader;
import id.ja_wa.feature.messaging.CallLinkQuery;
import id.ja_wa.feature.messaging.GroupMetadataQuery;
import id.ja_wa.feature.store.InMemoryStore;
import id.ja_wa.protocol.codec.BinaryNode;
import id.ja_wa.protocol.crypto.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Increment5WorkstreamBTest {

    // ---- Task 2: linkedParent parsing + resolution ----

    @Test
    void groupMetadataParsesLinkedParentChild() {
        BinaryNode group = new BinaryNode("group",
            Map.of("id", "111", "subject", "Sub"),
            List.of(new BinaryNode("linked_parent", Map.of("jid", "999@g.us"), null)));
        GroupMetadataQuery.GroupMetadata meta =
            GroupMetadataQuery.parse(new BinaryNode("iq", Map.of(), List.of(group)));
        assertThat(meta.id()).isEqualTo("111@g.us");
        assertThat(meta.linkedParent()).isEqualTo("999@g.us");
    }

    @Test
    void groupMetadataLinkedParentIsNullWhenAbsent() {
        BinaryNode group = new BinaryNode("group", Map.of("id", "999", "subject", "Comm"), List.of());
        GroupMetadataQuery.GroupMetadata meta =
            GroupMetadataQuery.parse(new BinaryNode("iq", Map.of(), List.of(group)));
        assertThat(meta.linkedParent()).isNull();
    }

    @Test
    void resolveCommunityJidPrefersLinkedParent() {
        assertThat(CommunityQuery.resolveCommunityJid("111@g.us", "999@g.us")).isEqualTo("999@g.us");
        assertThat(CommunityQuery.resolveCommunityJid("999@g.us", null)).isEqualTo("999@g.us");
        assertThat(CommunityQuery.resolveCommunityJid("999@g.us", "  ")).isEqualTo("999@g.us");
    }

    @Test
    void linkedGroupsResultCopiesList() {
        CommunityQuery.LinkedGroupsResult result = new CommunityQuery.LinkedGroupsResult(
            "999@g.us", true, List.of(new CommunityQuery.LinkedGroup("111@g.us", "Sub", 1L, null, 2L)));
        assertThat(result.communityJid()).isEqualTo("999@g.us");
        assertThat(result.isCommunity()).isTrue();
        assertThat(result.linkedGroups()).hasSize(1);
    }

    // ---- Task 3: product-image / biz-cover pure helpers (no live upload) ----

    @Test
    void rawUploadUrlUsesBaileysProductPathAndShaToken() {
        byte[] sha = Sha256.hash("img".getBytes(StandardCharsets.UTF_8));
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(sha);
        String url = MediaUploader.rawUploadUrl("mmg.whatsapp.net", "auth-token", sha,
            MediaUploader.PRODUCT_IMAGE_PATH);
        assertThat(url).isEqualTo("https://mmg.whatsapp.net/product/image/" + token
            + "?auth=auth-token&token=" + token);
        assertThat(MediaUploader.BIZ_COVER_PHOTO_PATH).isEqualTo("/pps/biz-cover-photo");
    }

    @Test
    void urlFromDirectPathUsesDefaultHost() {
        assertThat(MediaUploader.urlFromDirectPath("/d/abc")).isEqualTo("https://mmg.whatsapp.net/d/abc");
        assertThat(MediaUploader.urlFromDirectPath("d/abc")).isEqualTo("https://mmg.whatsapp.net/d/abc");
        assertThat(MediaUploader.urlFromDirectPath(null)).isNull();
        assertThat(MediaUploader.urlFromDirectPath("  ")).isNull();
    }

    @Test
    void whatsappHostedUrlDetectionMatchesBaileysCheck() {
        assertThat(MediaUploader.isWhatsAppHostedUrl("https://mmg.whatsapp.net/d/abc")).isTrue();
        assertThat(MediaUploader.isWhatsAppHostedUrl("https://example.invalid/img.jpg")).isFalse();
        assertThat(MediaUploader.isWhatsAppHostedUrl(null)).isFalse();
    }

    // ---- Task 4: store save/load client methods (temp dir) ----

    @Test
    void clientSaveLoadStoreRoundTripReplacesLiveStore(@TempDir Path dir) throws Exception {
        JaWaClient client = new JaWaClient(new FileAuthStore(dir.resolve("test.session")));
        client.store().upsertContact(new ContactsUpsert.Contact("111@s.whatsapp.net", "Jane", "jane",
            null, "111", "https://example.invalid/pic.jpg"));
        client.store().ensureChat("111@s.whatsapp.net", 12345L);

        Path file = dir.resolve("store.json");
        client.saveStore(file);
        assertThat(file).exists();

        // Live store gains an entry the snapshot does not have.
        client.store().upsertContact(new ContactsUpsert.Contact("222@s.whatsapp.net", "Bob", null,
            null, "222", null));
        assertThat(client.store().getContact("222@s.whatsapp.net")).isNotNull();

        assertThat(client.loadStore(file)).isTrue();
        assertThat(client.store().getContact("111@s.whatsapp.net").imgUrl())
            .isEqualTo("https://example.invalid/pic.jpg");
        assertThat(client.store().getChat("111@s.whatsapp.net").lastMessageTimestamp()).isEqualTo(12345L);
        // Wholesale replace: the post-snapshot entry is gone.
        assertThat(client.store().getContact("222@s.whatsapp.net")).isNull();

        assertThat(client.loadStore(dir.resolve("absent.json"))).isFalse();
        assertThat(client.store().getContact("111@s.whatsapp.net")).isNotNull();
    }

    @Test
    void replaceWithCopiesSnapshotAndKeepsCapAndBus() {
        InMemoryStore snapshot = new InMemoryStore();
        snapshot.upsertContact(new ContactsUpsert.Contact("111@s.whatsapp.net", "Jane", null,
            null, null, null));
        snapshot.ensureChat("111@s.whatsapp.net", 7L);

        InMemoryStore live = new InMemoryStore();
        live.upsertContact(new ContactsUpsert.Contact("222@s.whatsapp.net", "Bob", null,
            null, null, null));
        live.replaceWith(snapshot);
        assertThat(live.getContact("111@s.whatsapp.net")).isNotNull();
        assertThat(live.getContact("222@s.whatsapp.net")).isNull();
        assertThat(live.getChat("111@s.whatsapp.net").lastMessageTimestamp()).isEqualTo(7L);
    }

    // ---- Task 5: triage builders ----

    @Test
    void callLinkBuildAndTokenParse() {
        BinaryNode stanza = CallLinkQuery.buildCreate("tag1", "video", 1700000000L);
        assertThat(stanza.tag()).isEqualTo("call");
        assertThat(stanza.attr("to")).isEqualTo("@call");
        BinaryNode link = stanza.child("link_create");
        assertThat(link.attr("media")).isEqualTo("video");
        assertThat(link.child("event").attr("start_time")).isEqualTo("1700000000");

        BinaryNode noEvent = CallLinkQuery.buildCreate("tag2", "audio", null);
        assertThat(noEvent.child("link_create").child("event")).isNull();

        BinaryNode result = new BinaryNode("call", Map.of(), List.of(
            new BinaryNode("link_create", Map.of("token", "tok-123"), null)));
        assertThat(CallLinkQuery.parseToken(result)).isEqualTo("tok-123");
        assertThat(CallLinkQuery.parseToken(new BinaryNode("call", Map.of(), List.of()))).isNull();
    }

    @Test
    void communityV4InviteBuilders() {
        BinaryNode revoke = CommunityQuery.buildRevokeInviteV4("iq1", "999@g.us", "111@s.whatsapp.net");
        assertThat(revoke.attr("to")).isEqualTo("999@g.us");
        assertThat(revoke.attr("type")).isEqualTo("set");
        assertThat(revoke.child("revoke").child("participant").attr("jid"))
            .isEqualTo("111@s.whatsapp.net");

        BinaryNode accept = CommunityQuery.buildAcceptInviteV4("iq2", "999@g.us", "CODE",
            1700000000L, "888@s.whatsapp.net");
        BinaryNode acceptNode = accept.child("accept");
        assertThat(accept.attr("to")).isEqualTo("999@g.us");
        assertThat(acceptNode.attr("code")).isEqualTo("CODE");
        assertThat(acceptNode.attr("expiration")).isEqualTo("1700000000");
        assertThat(acceptNode.attr("admin")).isEqualTo("888@s.whatsapp.net");
    }
}
