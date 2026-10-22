package id.jawa.client;

import id.jawa.domain.model.AuthCreds;
import id.jawa.domain.model.Blocklist;
import id.jawa.domain.model.BlocklistItem;
import id.jawa.domain.model.BotListInfo;
import id.jawa.domain.model.BusinessMessageLinkTarget;
import id.jawa.domain.model.BusinessProfile;
import id.jawa.domain.model.CommunitySubGroup;
import id.jawa.domain.model.GroupJoinRequest;
import id.jawa.domain.model.Jid;
import id.jawa.domain.model.NewsletterMessage;
import id.jawa.domain.model.NewsletterMetadata;
import id.jawa.domain.model.OrderDetails;
import id.jawa.domain.model.ProfilePictureInfo;
import id.jawa.domain.model.StatusPrivacy;
import id.jawa.domain.model.WaConstants;
import id.jawa.domain.store.FileAuthStore;
import id.jawa.feature.media.MediaDownloader;
import id.jawa.feature.messaging.GroupAction;
import id.jawa.feature.messaging.GroupListQuery;
import id.jawa.feature.messaging.MessageEncoder;
import id.jawa.feature.messaging.MessageReceiver.Decoded;
import id.jawa.feature.messaging.UsyncQuery;
import id.jawa.proto.Wa;
import id.jawa.protocol.codec.BinaryNode;
import id.jawa.protocol.crypto.AesGcm;
import id.jawa.protocol.crypto.Bytes;
import id.jawa.protocol.crypto.Hkdf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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

        Wa.Message plainMsg = Wa.Message.newBuilder()
            .setConversation("hello ephemeral")
            .build();

        Wa.Message wrapped = client.wrapEphemeralIfNeeded(chatJid, plainMsg);
        assertThat(wrapped.hasEphemeralMessage()).isTrue();
        assertThat(wrapped.getEphemeralMessage().getMessage().getConversation()).isEqualTo("hello ephemeral");

        Wa.Message doubleWrapAttempt = client.wrapEphemeralIfNeeded(chatJid, wrapped);
        assertThat(doubleWrapAttempt).isSameAs(wrapped);

        Wa.Message protocolMsg = Wa.Message.newBuilder()
            .setProtocolMessage(Wa.Message.ProtocolMessage.newBuilder()
                .setType(Wa.Message.ProtocolMessage.Type.EPHEMERAL_SETTING)
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
        var inviteGet = GroupAction.buildInviteLink("iq1", "12345@g.us", false);
        assertThat(inviteGet.tag()).isEqualTo("iq");
        assertThat(inviteGet.attr("type")).isEqualTo("get");
        assertThat(inviteGet.attr("xmlns")).isEqualTo("w:g2");
        assertThat(inviteGet.child("invite")).isNotNull();

        var inviteReset = GroupAction.buildInviteLink("iq2", "12345@g.us", true);
        assertThat(inviteReset.attr("type")).isEqualTo("set");

        var linkInfo = GroupAction.buildLinkInfo("iq3", "ABCDEF12345");
        assertThat(linkInfo.attr("type")).isEqualTo("get");
        assertThat(linkInfo.attr("to")).isEqualTo("g.us");
        assertThat(linkInfo.child("invite").attr("code")).isEqualTo("ABCDEF12345");

        var join = GroupAction.buildJoinWithLink("iq4", "ABCDEF12345");
        assertThat(join.attr("type")).isEqualTo("set");
        assertThat(join.attr("to")).isEqualTo("g.us");
        assertThat(join.child("invite").attr("code")).isEqualTo("ABCDEF12345");

        var locked = GroupAction.buildSetLocked("iq5", "12345@g.us", true);
        assertThat(locked.child("locked")).isNotNull();

        var unlocked = GroupAction.buildSetLocked("iq6", "12345@g.us", false);
        assertThat(unlocked.child("unlocked")).isNotNull();

        var announce = GroupAction.buildSetAnnounce("iq7", "12345@g.us", true);
        assertThat(announce.child("announcement")).isNotNull();

        var notAnnounce = GroupAction.buildSetAnnounce("iq8", "12345@g.us", false);
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

        var q = UsyncQuery.buildUserInfoQuery("iq1", "sid1", List.of("628123456789@s.whatsapp.net"));
        assertThat(q.attr("xmlns")).isEqualTo("usync");
        assertThat(q.child("usync")).isNotNull();

        var groupIq = GroupListQuery.buildGroupInfoQuery("iq2", "12345@g.us");
        assertThat(groupIq.attr("to")).isEqualTo("12345@g.us");
        assertThat(groupIq.child("query").attr("request")).isEqualTo("interactive");
    }

    @Test
    void testNewFeatureAdditionsAndJidBare(@TempDir Path tempDir) {
        FileAuthStore store = new FileAuthStore(tempDir.resolve("test.session"));
        JaWaClient client = new JaWaClient(store);

        assertThat(Jid.bare("628123456789:2@s.whatsapp.net"))
            .isEqualTo("628123456789@s.whatsapp.net");
        assertThat(Jid.bare("120363123456@g.us"))
            .isEqualTo("120363123456@g.us");
        assertThat(Jid.parse("628123456789:2@s.whatsapp.net").toBareString())
            .isEqualTo("628123456789@s.whatsapp.net");

        var req = new GroupJoinRequest("628123456789@s.whatsapp.net", 1700000000L);
        assertThat(req.jid()).isEqualTo("628123456789@s.whatsapp.net");
        assertThat(req.requestedAt()).isEqualTo(1700000000L);

        var bizTarget = new BusinessMessageLinkTarget(
            "628123456789@s.whatsapp.net", "Test Store", "Hello", true, "Verified Store", "HIGH"
        );
        assertThat(bizTarget.isSigned()).isTrue();
        assertThat(bizTarget.message()).isEqualTo("Hello");

        var bizProf = new BusinessProfile(
            "628123456789@s.whatsapp.net", "Jakarta", "store@example.com", "Official Store",
            List.of("https://example.com"),
            List.of(new BusinessProfile.Category("cat1", "Shopping")),
            "Asia/Jakarta",
            List.of(new BusinessProfile.BusinessHourConfig("mon", "open", "09:00", "17:00")),
            Map.of("opt1", "val1")
        );
        assertThat(bizProf.email()).isEqualTo("store@example.com");
        assertThat(bizProf.websites()).hasSize(1);
        assertThat(bizProf.categories()).hasSize(1);

        var newsMsg = new NewsletterMessage(
            12345L, "3EB0ABC", "text", 1700000000L, 50, Map.of("👍", 5), null
        );
        assertThat(newsMsg.serverId()).isEqualTo(12345L);
        assertThat(newsMsg.viewsCount()).isEqualTo(50);
        assertThat(newsMsg.reactionCounts().get("👍")).isEqualTo(5);
    }

    @Test
    void testNewsletterMetadataAndStoreClear(@TempDir Path tempDir) throws Exception {
        var storeFile = tempDir.resolve("test-store.session");
        var store = new FileAuthStore(storeFile);
        var creds = AuthCreds.generate();
        creds.meJid = "628123456789:1@s.whatsapp.net";
        creds.pushName = "TestBot";
        store.save(creds);

        assertThat(Files.exists(storeFile)).isTrue();
        store.clear();
        assertThat(Files.exists(storeFile)).isFalse();

        var meta = new NewsletterMetadata(
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

        var price = new OrderDetails.OrderPrice(10000L, 10000L, "IDR", "paid");
        var product = new OrderDetails.OrderProduct("p1", "Item", 10000L, "IDR", 1, "img1", "https://img");
        var order = new OrderDetails("ord123", 1700000000L, "cat1", price, List.of(product));
        assertThat(order.id()).isEqualTo("ord123");
        assertThat(order.price().total()).isEqualTo(10000L);
        assertThat(order.products()).hasSize(1);
    }

    @Test
    void testBlocklistAndStatusPrivacyModels(@TempDir Path tempDir) {
        var item = new BlocklistItem("123@lid", "628123@s.whatsapp.net", true);
        assertThat(item.lid()).isEqualTo("123@lid");
        assertThat(item.pn()).isEqualTo("628123@s.whatsapp.net");
        assertThat(item.active()).isTrue();

        var blocklist = new Blocklist("hash1", "lid", List.of(item));
        assertThat(blocklist.dhash()).isEqualTo("hash1");
        assertThat(blocklist.addressingMode()).isEqualTo("lid");
        assertThat(blocklist.items()).hasSize(1);

        var statusPriv = new StatusPrivacy("blacklist", true, List.of("user1@s.whatsapp.net"));
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
        var subGroup = new CommunitySubGroup("120363@g.us", "General", 1700000000L, true);
        assertThat(subGroup.jid()).isEqualTo("120363@g.us");
        assertThat(subGroup.subject()).isEqualTo("General");
        assertThat(subGroup.nameSetAt()).isEqualTo(1700000000L);
        assertThat(subGroup.defaultSubGroup()).isTrue();

        var picInfo = new ProfilePictureInfo("pic123", "https://img.whatsapp.net/p.jpg", "image", "/mms/image/123");
        assertThat(picInfo.id()).isEqualTo("pic123");
        assertThat(picInfo.url()).isEqualTo("https://img.whatsapp.net/p.jpg");
        assertThat(picInfo.type()).isEqualTo("image");
        assertThat(picInfo.directPath()).isEqualTo("/mms/image/123");

        var botInfo = new BotListInfo("p_bot_1", "13135550001@s.whatsapp.net");
        assertThat(botInfo.personaId()).isEqualTo("p_bot_1");
        assertThat(botInfo.botJid()).isEqualTo("13135550001@s.whatsapp.net");

        var store = new FileAuthStore(tempDir.resolve("test-store3.session"));
        JaWaClient client = new JaWaClient(store);
        assertThat(client.groups()).isNotNull();
        assertThat(client.profile()).isNotNull();
    }

    @Test
    void testProfilePictureQueryConstruction() {
        // Raw phone number without @
        BinaryNode q1 = JaWaClient.buildProfilePictureQuery("iq1", "923224559543", true);
        assertThat(q1.tag()).isEqualTo("iq");
        assertThat(q1.attr("to")).isEqualTo("s.whatsapp.net");
        assertThat(q1.attr("target")).isEqualTo("923224559543@s.whatsapp.net");
        assertThat(q1.attr("type")).isEqualTo("get");
        assertThat(q1.attr("xmlns")).isEqualTo("w:profile:picture");
        assertThat(q1.attr("id")).isEqualTo("iq1");
        BinaryNode pic1 = q1.child("picture");
        assertThat(pic1).isNotNull();
        assertThat(pic1.attr("type")).isEqualTo("preview");
        assertThat(pic1.attr("query")).isEqualTo("url");

        // Multi-device PN JID normalized to bare
        BinaryNode q2 = JaWaClient.buildProfilePictureQuery("iq2", "923224559543:1@s.whatsapp.net", false);
        assertThat(q2.attr("to")).isEqualTo("s.whatsapp.net");
        assertThat(q2.attr("target")).isEqualTo("923224559543@s.whatsapp.net");
        BinaryNode pic2 = q2.child("picture");
        assertThat(pic2).isNotNull();
        assertThat(pic2.attr("type")).isEqualTo("image");
        assertThat(pic2.attr("query")).isEqualTo("url");

        // Group JID
        BinaryNode q3 = JaWaClient.buildProfilePictureQuery("iq3", "120363012345678901@g.us", true);
        assertThat(q3.attr("to")).isEqualTo("s.whatsapp.net");
        assertThat(q3.attr("target")).isEqualTo("120363012345678901@g.us");

        // LID JID
        BinaryNode q4 = JaWaClient.buildProfilePictureQuery("iq4", "1234567890:0@lid", true);
        assertThat(q4.attr("to")).isEqualTo("s.whatsapp.net");
        assertThat(q4.attr("target")).isEqualTo("1234567890@lid");
    }

    @Test
    void testProfilePictureResponseParsing() {
        // Success response
        BinaryNode successResp = new BinaryNode("iq", Map.of("type", "result", "id", "iq1"), List.of(
            new BinaryNode("picture", Map.of(
                "id", "rev99",
                "url", "https://pps.whatsapp.net/v/t61/example.jpg",
                "type", "preview",
                "direct_path", "/v/t61/example.jpg"
            ), null)
        ));
        ProfilePictureInfo info = JaWaClient.parseProfilePictureResponse(successResp);
        assertThat(info).isNotNull();
        assertThat(info.id()).isEqualTo("rev99");
        assertThat(info.url()).isEqualTo("https://pps.whatsapp.net/v/t61/example.jpg");
        assertThat(info.type()).isEqualTo("preview");
        assertThat(info.directPath()).isEqualTo("/v/t61/example.jpg");

        // Error response (e.g. 404 item-not-found or 401 not-authorized)
        BinaryNode err404 = new BinaryNode("iq", Map.of("type", "error", "id", "iq2"), List.of(
            new BinaryNode("error", Map.of("code", "404", "text", "item-not-found"), null)
        ));
        assertThat(JaWaClient.parseProfilePictureResponse(err404)).isNull();

        BinaryNode err401 = new BinaryNode("iq", Map.of("type", "error", "id", "iq3"), List.of(
            new BinaryNode("error", Map.of("code", "401", "text", "not-authorized"), null)
        ));
        assertThat(JaWaClient.parseProfilePictureResponse(err401)).isNull();

        // Status 204 (no picture set)
        BinaryNode noPic204 = new BinaryNode("iq", Map.of("type", "result", "id", "iq4"), List.of(
            new BinaryNode("picture", Map.of("status", "204"), null)
        ));
        assertThat(JaWaClient.parseProfilePictureResponse(noPic204)).isNull();

        // Empty result stanza without picture child
        BinaryNode emptyResult = new BinaryNode("iq", Map.of("type", "result", "id", "iq5"), List.of());
        assertThat(JaWaClient.parseProfilePictureResponse(emptyResult)).isNull();
    }

    @Test
    void testStickerMessageEncoder() {
        byte[] mediaKey = new byte[32];
        byte[] sha256 = new byte[32];
        byte[] encSha256 = new byte[32];
        var msg = MessageEncoder.stickerMessage(
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

        byte[] mediaKey = Bytes.random(32);
        String msgId = "3EB0ABC123456789";

        Wa.MediaRetryNotification original = Wa.MediaRetryNotification.newBuilder()
            .setStanzaId(msgId)
            .setDirectPath("/v/t62/reuploaded_media")
            .setResult(Wa.MediaRetryNotification.ResultType.SUCCESS)
            .build();

        byte[] plaintext = original.toByteArray();
        byte[] retryKey = Hkdf.derive(
            mediaKey, null, "WhatsApp Media Retry Notification".getBytes(StandardCharsets.UTF_8), 32);
        byte[] iv = Bytes.random(12);
        byte[] ciphertext = AesGcm.encrypt(
            retryKey, iv, plaintext, msgId.getBytes(StandardCharsets.UTF_8));

        Wa.MediaRetryNotification decrypted =
            JaWaClient.decryptMediaRetryNotification(ciphertext, iv, msgId, mediaKey);

        assertThat(decrypted).isNotNull();
        assertThat(decrypted.getStanzaId()).isEqualTo(msgId);
        assertThat(decrypted.getDirectPath()).isEqualTo("/v/t62/reuploaded_media");
        assertThat(decrypted.getResult()).isEqualTo(Wa.MediaRetryNotification.ResultType.SUCCESS);

        assertThat(client.messages()).isNotNull();
        assertThat(client.media()).isNotNull();
    }

    @Test
    void testWaConstantsVersionAndNoticeAcceptance(@TempDir Path tempDir) {
        int[] originalVer = WaConstants.getVersion();
        assertThat(originalVer).hasSize(3);

        WaConstants.setVersion(2, 3000, 999999);
        int[] updatedVer = WaConstants.getVersion();
        assertThat(updatedVer[2]).isEqualTo(999999);

        WaConstants.setVersion(originalVer[0], originalVer[1], originalVer[2]);

        FileAuthStore store = new FileAuthStore(tempDir.resolve("test-store5.session"));
        JaWaClient client = new JaWaClient(store);
        assertThat(client.syncVersion()).isTrue();
        client.syncVersion(false);
        assertThat(client.syncVersion()).isFalse();

        int[] fetched = WaConstants.fetchLatestVersion();
        assertThat(fetched).hasSize(3);
        assertThat(fetched[0]).isEqualTo(2);
        assertThat(fetched[1]).isEqualTo(3000);
        assertThat(fetched[2]).isGreaterThan(1000000000);

        int[] clientFetched = JaWaClient.fetchLatestVersion();
        assertThat(clientFetched).containsExactly(fetched);

        assertThat(client.newsletters().acceptTOSNotice("20601218", "5")).isNotNull();
        assertThat(client.newsletters().acceptNewsletterTOSNotice()).isNotNull();
    }

    @Test
    void testMediaDownloaderUnwrapping() {
        var plain = Wa.Message.newBuilder()
            .setImageMessage(Wa.Message.ImageMessage.newBuilder()
                .setUrl("")
                .setDirectPath("")
                .build())
            .build();
        var wrapped = Wa.Message.newBuilder()
            .setEphemeralMessage(Wa.Message.FutureProofMessage.newBuilder()
                .setMessage(plain)
                .build())
            .build();

        byte[] downloaded = MediaDownloader.download(wrapped);
        assertThat(downloaded).isNull();

        Decoded decoded =
            new Decoded(
                "628123@s.whatsapp.net", null, "3EB0123", "text", plain, null, null, 1700000000L);
        byte[] downloadedDecoded = MediaDownloader.download(decoded);
        assertThat(downloadedDecoded).isNull();
    }
}
