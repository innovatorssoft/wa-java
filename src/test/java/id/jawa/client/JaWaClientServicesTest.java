package id.jawa.client;

import id.jawa.domain.store.FileAuthStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JaWaClientServicesTest {

    @Test
    void subServicesAreProperlyInitialized(@TempDir Path tempDir) {
        FileAuthStore store = new FileAuthStore(tempDir.resolve("test.session"));
        JaWaClient client = new JaWaClient(store);

        assertThat(client.messages()).isNotNull();
        assertThat(client.groups()).isNotNull();
        assertThat(client.media()).isNotNull();
        assertThat(client.newsletters()).isNotNull();
        assertThat(client.profile()).isNotNull();
        assertThat(client.chats()).isNotNull();

        assertThat(client.messages()).isSameAs(client.messages());
        assertThat(client.groups()).isSameAs(client.groups());
        assertThat(client.media()).isSameAs(client.media());
        assertThat(client.newsletters()).isSameAs(client.newsletters());
        assertThat(client.profile()).isSameAs(client.profile());
        assertThat(client.chats()).isSameAs(client.chats());
    }

    @Test
    void generateMessageIdHasWebPrefixAndValidFormat(@TempDir Path tempDir) {
        FileAuthStore store = new FileAuthStore(tempDir.resolve("test.session"));
        JaWaClient client = new JaWaClient(store);
        String id1 = client.generateMessageId();
        String id2 = client.messages().generateMessageId();

        assertThat(id1).startsWith("3EB0").hasSize(22);
        assertThat(id2).startsWith("3EB0").hasSize(22);
        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    void setDisappearingTimerReturnsFutureForValidInputs(@TempDir Path tempDir) {
        FileAuthStore store = new FileAuthStore(tempDir.resolve("test.session"));
        JaWaClient client = new JaWaClient(store);
        assertThat(client.chats().setDisappearingTimer(null, 86400)).isNotNull();
        assertThat(client.groups().setDisappearingTimer(null, 0)).isNotNull();
    }

    @Test
    void autoEphemeralConfigurationAndWrapping(@TempDir Path tempDir) {
        FileAuthStore store = new FileAuthStore(tempDir.resolve("test.session"));
        JaWaClient client = new JaWaClient(store);

        assertThat(client.isAutoEphemeral()).isTrue();
        assertThat(client.autoEphemeral()).isTrue();

        String chatJid = "628123456789@s.whatsapp.net";
        assertThat(client.getChatEphemeralTimer(chatJid)).isEqualTo(0);

        client.setChatEphemeralTimer(chatJid, 86400);
        assertThat(client.getChatEphemeralTimer(chatJid)).isEqualTo(86400);
        assertThat(client.chats().getDisappearingTimer(chatJid)).isEqualTo(86400);

        id.jawa.proto.Wa.Message plainMsg = id.jawa.proto.Wa.Message.newBuilder()
            .setConversation("hello ephemeral")
            .build();

        id.jawa.proto.Wa.Message wrapped = client.wrapEphemeralIfNeeded(chatJid, plainMsg);
        assertThat(wrapped.hasEphemeralMessage()).isTrue();
        assertThat(wrapped.getEphemeralMessage().getMessage().getConversation()).isEqualTo("hello ephemeral");

        id.jawa.proto.Wa.Message doubleWrapAttempt = client.wrapEphemeralIfNeeded(chatJid, wrapped);
        assertThat(doubleWrapAttempt).isSameAs(wrapped);

        id.jawa.proto.Wa.Message protocolMsg = id.jawa.proto.Wa.Message.newBuilder()
            .setProtocolMessage(id.jawa.proto.Wa.Message.ProtocolMessage.newBuilder()
                .setType(id.jawa.proto.Wa.Message.ProtocolMessage.Type.EPHEMERAL_SETTING)
                .setEphemeralExpiration(86400))
            .build();
        assertThat(client.wrapEphemeralIfNeeded(chatJid, protocolMsg)).isSameAs(protocolMsg);

        client.autoEphemeral(false);
        assertThat(client.isAutoEphemeral()).isFalse();
        assertThat(client.wrapEphemeralIfNeeded(chatJid, plainMsg)).isSameAs(plainMsg);

        client.autoEphemeral(true);
        client.setChatEphemeralTimer(chatJid, 0);
        assertThat(client.getChatEphemeralTimer(chatJid)).isEqualTo(0);
        assertThat(client.wrapEphemeralIfNeeded(chatJid, plainMsg)).isSameAs(plainMsg);
    }

    @Test
    void groupActionBuildersGenerateValidBinaryNodes() {
        var inviteGet = id.jawa.feature.messaging.GroupAction.buildInviteLink("iq1", "12345@g.us", false);
        assertThat(inviteGet.tag()).isEqualTo("iq");
        assertThat(inviteGet.attr("type")).isEqualTo("get");
        assertThat(inviteGet.attr("xmlns")).isEqualTo("w:g2");
        assertThat(inviteGet.child("invite")).isNotNull();

        var inviteReset = id.jawa.feature.messaging.GroupAction.buildInviteLink("iq2", "12345@g.us", true);
        assertThat(inviteReset.attr("type")).isEqualTo("set");

        var linkInfo = id.jawa.feature.messaging.GroupAction.buildLinkInfo("iq3", "ABCDEF12345");
        assertThat(linkInfo.attr("type")).isEqualTo("get");
        assertThat(linkInfo.attr("to")).isEqualTo("g.us");
        assertThat(linkInfo.child("invite").attr("code")).isEqualTo("ABCDEF12345");

        var join = id.jawa.feature.messaging.GroupAction.buildJoinWithLink("iq4", "ABCDEF12345");
        assertThat(join.attr("type")).isEqualTo("set");
        assertThat(join.attr("to")).isEqualTo("g.us");
        assertThat(join.child("invite").attr("code")).isEqualTo("ABCDEF12345");

        var locked = id.jawa.feature.messaging.GroupAction.buildSetLocked("iq5", "12345@g.us", true);
        assertThat(locked.child("locked")).isNotNull();

        var unlocked = id.jawa.feature.messaging.GroupAction.buildSetLocked("iq6", "12345@g.us", false);
        assertThat(unlocked.child("unlocked")).isNotNull();

        var announce = id.jawa.feature.messaging.GroupAction.buildSetAnnounce("iq7", "12345@g.us", true);
        assertThat(announce.child("announcement")).isNotNull();

        var notAnnounce = id.jawa.feature.messaging.GroupAction.buildSetAnnounce("iq8", "12345@g.us", false);
        assertThat(notAnnounce.child("not_announcement")).isNotNull();
    }

    @Test
    void autoIgnoreOfflineMessagesConfiguration(@TempDir Path tempDir) {
        FileAuthStore store = new FileAuthStore(tempDir.resolve("test.session"));
        JaWaClient client = new JaWaClient(store);

        assertThat(client.isAutoIgnoreOfflineMessages()).isTrue();
        assertThat(client.autoIgnoreOfflineMessages()).isTrue();
        assertThat(client.offlineMessageGracePeriodSec()).isEqualTo(2);

        client.autoIgnoreOfflineMessages(false);
        assertThat(client.autoIgnoreOfflineMessages()).isFalse();

        client.offlineMessageGracePeriodSec(5);
        assertThat(client.offlineMessageGracePeriodSec()).isEqualTo(5);
    }

    @Test
    void privacyServiceAndProfileAdditionsInitialized(@TempDir Path tempDir) {
        FileAuthStore store = new FileAuthStore(tempDir.resolve("test.session"));
        JaWaClient client = new JaWaClient(store);

        assertThat(client.privacy()).isNotNull();
        assertThat(client.privacy()).isSameAs(client.privacy());

        var q = id.jawa.feature.messaging.UsyncQuery.buildUserInfoQuery("iq1", "sid1", java.util.List.of("628123456789@s.whatsapp.net"));
        assertThat(q.attr("xmlns")).isEqualTo("usync");
        assertThat(q.child("usync")).isNotNull();

        var groupIq = id.jawa.feature.messaging.GroupListQuery.buildGroupInfoQuery("iq2", "12345@g.us");
        assertThat(groupIq.attr("to")).isEqualTo("12345@g.us");
        assertThat(groupIq.child("query").attr("request")).isEqualTo("interactive");
    }

    @Test
    void testNewFeatureAdditionsAndJidBare(@TempDir Path tempDir) {
        FileAuthStore store = new FileAuthStore(tempDir.resolve("test.session"));
        JaWaClient client = new JaWaClient(store);

        assertThat(id.jawa.domain.model.Jid.bare("628123456789:2@s.whatsapp.net"))
            .isEqualTo("628123456789@s.whatsapp.net");
        assertThat(id.jawa.domain.model.Jid.bare("120363123456@g.us"))
            .isEqualTo("120363123456@g.us");
        assertThat(id.jawa.domain.model.Jid.parse("628123456789:2@s.whatsapp.net").toBareString())
            .isEqualTo("628123456789@s.whatsapp.net");

        var req = new id.jawa.domain.model.GroupJoinRequest("628123456789@s.whatsapp.net", 1700000000L);
        assertThat(req.jid()).isEqualTo("628123456789@s.whatsapp.net");
        assertThat(req.requestedAt()).isEqualTo(1700000000L);

        var bizTarget = new id.jawa.domain.model.BusinessMessageLinkTarget(
            "628123456789@s.whatsapp.net", "Test Store", "Hello", true, "Verified Store", "HIGH"
        );
        assertThat(bizTarget.isSigned()).isTrue();
        assertThat(bizTarget.message()).isEqualTo("Hello");

        var bizProf = new id.jawa.domain.model.BusinessProfile(
            "628123456789@s.whatsapp.net", "Jakarta", "store@example.com", "Official Store",
            java.util.List.of("https://example.com"),
            java.util.List.of(new id.jawa.domain.model.BusinessProfile.Category("cat1", "Shopping")),
            "Asia/Jakarta",
            java.util.List.of(new id.jawa.domain.model.BusinessProfile.BusinessHourConfig("mon", "open", "09:00", "17:00")),
            java.util.Map.of("opt1", "val1")
        );
        assertThat(bizProf.email()).isEqualTo("store@example.com");
        assertThat(bizProf.websites()).hasSize(1);
        assertThat(bizProf.categories()).hasSize(1);

        var newsMsg = new id.jawa.domain.model.NewsletterMessage(
            12345L, "3EB0ABC", "text", 1700000000L, 50, java.util.Map.of("👍", 5), null
        );
        assertThat(newsMsg.serverId()).isEqualTo(12345L);
        assertThat(newsMsg.viewsCount()).isEqualTo(50);
        assertThat(newsMsg.reactionCounts().get("👍")).isEqualTo(5);
    }

    @Test
    void testNewsletterMetadataAndStoreClear(@TempDir Path tempDir) throws Exception {
        var storeFile = tempDir.resolve("test-store.session");
        var store = new FileAuthStore(storeFile);
        var creds = id.jawa.domain.model.AuthCreds.generate();
        creds.meJid = "628123456789:1@s.whatsapp.net";
        creds.pushName = "TestBot";
        store.save(creds);

        assertThat(java.nio.file.Files.exists(storeFile)).isTrue();
        store.clear();
        assertThat(java.nio.file.Files.exists(storeFile)).isFalse();

        var meta = new id.jawa.domain.model.NewsletterMetadata(
            "120363123456@newsletter", "Tech News", "Latest Updates", "Invite123",
            1700000000L, 1000, "ADMIN", false, "https://example.com/pic.jpg"
        );
        assertThat(meta.id()).isEqualTo("120363123456@newsletter");
        assertThat(meta.name()).isEqualTo("Tech News");
        assertThat(meta.subscriberCount()).isEqualTo(1000);
        assertThat(meta.role()).isEqualTo("ADMIN");
        assertThat(meta.muted()).isFalse();

        JaWaClient client = new JaWaClient(store);
        assertThat(client.newsletters()).isNotNull();
        assertThat(client.profile()).isNotNull();

        var price = new id.jawa.domain.model.OrderDetails.OrderPrice(10000L, 10000L, "IDR", "paid");
        var product = new id.jawa.domain.model.OrderDetails.OrderProduct("p1", "Item", 10000L, "IDR", 1, "img1", "https://img");
        var order = new id.jawa.domain.model.OrderDetails("ord123", 1700000000L, "cat1", price, java.util.List.of(product));
        assertThat(order.id()).isEqualTo("ord123");
        assertThat(order.price().total()).isEqualTo(10000L);
        assertThat(order.products()).hasSize(1);
    }

    @Test
    void testBlocklistAndStatusPrivacyModels(@TempDir Path tempDir) {
        var item = new id.jawa.domain.model.BlocklistItem("123@lid", "628123@s.whatsapp.net", true);
        assertThat(item.lid()).isEqualTo("123@lid");
        assertThat(item.pn()).isEqualTo("628123@s.whatsapp.net");
        assertThat(item.active()).isTrue();

        var blocklist = new id.jawa.domain.model.Blocklist("hash1", "lid", java.util.List.of(item));
        assertThat(blocklist.dhash()).isEqualTo("hash1");
        assertThat(blocklist.addressingMode()).isEqualTo("lid");
        assertThat(blocklist.items()).hasSize(1);

        var statusPriv = new id.jawa.domain.model.StatusPrivacy("blacklist", true, java.util.List.of("user1@s.whatsapp.net"));
        assertThat(statusPriv.type()).isEqualTo("blacklist");
        assertThat(statusPriv.isDefault()).isTrue();
        assertThat(statusPriv.list()).containsExactly("user1@s.whatsapp.net");

        var store = new FileAuthStore(tempDir.resolve("test-store2.session"));
        JaWaClient client = new JaWaClient(store);
        assertThat(client.privacy()).isNotNull();
        assertThat(client.chats()).isNotNull();
        assertThat(client.messages()).isNotNull();
    }

    @Test
    void testCommunitySubGroupAndProfilePictureModels(@TempDir Path tempDir) {
        var subGroup = new id.jawa.domain.model.CommunitySubGroup("120363@g.us", "General", 1700000000L, true);
        assertThat(subGroup.jid()).isEqualTo("120363@g.us");
        assertThat(subGroup.subject()).isEqualTo("General");
        assertThat(subGroup.nameSetAt()).isEqualTo(1700000000L);
        assertThat(subGroup.defaultSubGroup()).isTrue();

        var picInfo = new id.jawa.domain.model.ProfilePictureInfo("pic123", "https://img.whatsapp.net/p.jpg", "image", "/mms/image/123");
        assertThat(picInfo.id()).isEqualTo("pic123");
        assertThat(picInfo.url()).isEqualTo("https://img.whatsapp.net/p.jpg");
        assertThat(picInfo.type()).isEqualTo("image");
        assertThat(picInfo.directPath()).isEqualTo("/mms/image/123");

        var botInfo = new id.jawa.domain.model.BotListInfo("p_bot_1", "13135550001@s.whatsapp.net");
        assertThat(botInfo.personaId()).isEqualTo("p_bot_1");
        assertThat(botInfo.botJid()).isEqualTo("13135550001@s.whatsapp.net");

        var store = new FileAuthStore(tempDir.resolve("test-store3.session"));
        JaWaClient client = new JaWaClient(store);
        assertThat(client.groups()).isNotNull();
        assertThat(client.profile()).isNotNull();
    }

    @Test
    void testStickerMessageEncoder() {
        byte[] mediaKey = new byte[32];
        byte[] sha256 = new byte[32];
        byte[] encSha256 = new byte[32];
        var msg = id.jawa.feature.messaging.MessageEncoder.stickerMessage(
            "https://mmg.whatsapp.net/v/t62/123",
            "/v/t62/123",
            mediaKey,
            sha256,
            encSha256,
            12345L,
            true
        );
        assertThat(msg.hasStickerMessage()).isTrue();
        var sticker = msg.getStickerMessage();
        assertThat(sticker.getUrl()).isEqualTo("https://mmg.whatsapp.net/v/t62/123");
        assertThat(sticker.getDirectPath()).isEqualTo("/v/t62/123");
        assertThat(sticker.getMimetype()).isEqualTo("image/webp");
        assertThat(sticker.getFileLength()).isEqualTo(12345L);
        assertThat(sticker.getIsAnimated()).isTrue();
    }

    @Test
    void testMediaRetryNotificationCryptoRoundtrip(@TempDir Path tempDir) {
        FileAuthStore store = new FileAuthStore(tempDir.resolve("test-store4.session"));
        JaWaClient client = new JaWaClient(store);

        byte[] mediaKey = id.jawa.protocol.crypto.Bytes.random(32);
        String msgId = "3EB0ABC123456789";

        id.jawa.proto.Wa.MediaRetryNotification original = id.jawa.proto.Wa.MediaRetryNotification.newBuilder()
            .setStanzaId(msgId)
            .setDirectPath("/v/t62/reuploaded_media")
            .setResult(id.jawa.proto.Wa.MediaRetryNotification.ResultType.SUCCESS)
            .build();

        byte[] plaintext = original.toByteArray();
        byte[] retryKey = id.jawa.protocol.crypto.Hkdf.derive(
            mediaKey, null, "WhatsApp Media Retry Notification".getBytes(java.nio.charset.StandardCharsets.UTF_8), 32);
        byte[] iv = id.jawa.protocol.crypto.Bytes.random(12);
        byte[] ciphertext = id.jawa.protocol.crypto.AesGcm.encrypt(
            retryKey, iv, plaintext, msgId.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        id.jawa.proto.Wa.MediaRetryNotification decrypted =
            JaWaClient.decryptMediaRetryNotification(ciphertext, iv, msgId, mediaKey);

        assertThat(decrypted).isNotNull();
        assertThat(decrypted.getStanzaId()).isEqualTo(msgId);
        assertThat(decrypted.getDirectPath()).isEqualTo("/v/t62/reuploaded_media");
        assertThat(decrypted.getResult()).isEqualTo(id.jawa.proto.Wa.MediaRetryNotification.ResultType.SUCCESS);

        assertThat(client.messages()).isNotNull();
        assertThat(client.media()).isNotNull();
    }
}
