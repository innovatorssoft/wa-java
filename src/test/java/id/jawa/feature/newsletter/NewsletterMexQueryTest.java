package id.jawa.feature.newsletter;

import id.jawa.domain.model.NewsletterMessage;
import id.jawa.domain.model.NewsletterMetadata;
import id.jawa.proto.Wa;
import id.jawa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NewsletterMexQueryTest {

    @Test
    void createUsesBaileysQueryIdAndVariables() {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.create("Daily News", "All the news");
        assertThat(req.queryId()).isEqualTo("8823471724422422");
        assertThat(req.dataPath()).isEqualTo("xwa2_newsletter_create");
        assertThat(req.jsonVariables()).isEqualTo(
            "{\"input\":{\"name\":\"Daily News\",\"description\":\"All the news\"}}");
    }

    @Test
    void createWithNullDescriptionSendsNull() {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.create("Daily News", null);
        assertThat(req.jsonVariables()).contains("\"description\":null");
    }

    @Test
    void createEscapesQuotesInName() {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.create("Say \"hi\"", null);
        assertThat(req.jsonVariables()).contains("Say \\\"hi\\\"");
    }

    @Test
    void updateIncludesOnlyNonNullFieldsPlusSettingsNull() {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.update("123@newsletter", "New Name", null, null);
        assertThat(req.queryId()).isEqualTo("24250201037901610");
        assertThat(req.jsonVariables()).isEqualTo(
            "{\"newsletter_id\":\"123@newsletter\",\"updates\":{\"name\":\"New Name\",\"settings\":null}}");
    }

    @Test
    void removePictureSendsEmptyPictureString() {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.removePicture("123@newsletter");
        assertThat(req.jsonVariables()).contains("\"picture\":\"\"");
    }

    @Test
    void demoteAndDeleteUseBaileysQueryIds() {
        assertThat(NewsletterMexQuery.demote("123@newsletter", "456@s.whatsapp.net").queryId())
            .isEqualTo("6551828931592903");
        assertThat(NewsletterMexQuery.demote("123@newsletter", "456@s.whatsapp.net").jsonVariables())
            .contains("\"user_id\":\"456@s.whatsapp.net\"");
        assertThat(NewsletterMexQuery.delete("123@newsletter").queryId()).isEqualTo("30062808666639665");
        assertThat(NewsletterMexQuery.changeOwner("123@newsletter", "789@s.whatsapp.net").queryId())
            .isEqualTo("7341777602580933");
        assertThat(NewsletterMexQuery.adminCount("123@newsletter").queryId()).isEqualTo("7130823597031706");
        assertThat(NewsletterMexQuery.subscribers("123@newsletter").queryId()).isEqualTo("9783111038412085");
        assertThat(NewsletterMexQuery.subscribed().jsonVariables()).isEqualTo("{}");
    }

    @Test
    void cappingInfoUsesBaileysShape() {
        NewsletterMexQuery.MexRequest req = NewsletterMexQuery.messageCappingInfo();
        assertThat(req.queryId()).isEqualTo("24503548349331633");
        assertThat(req.dataPath()).isEqualTo("xwa2_message_capping_info");
        assertThat(req.jsonVariables()).isEqualTo("{\"input\":{\"type\":\"INDIVIDUAL_NEW_CHAT_MSG\"}}");
    }

    @Test
    void blankJidRejected() {
        assertThatThrownBy(() -> NewsletterMexQuery.delete("  "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NewsletterMexQuery.create("", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseCreateResponseMapsBaileysFields() {
        String json = "{\"data\":{\"xwa2_newsletter_create\":{\"id\":\"123@newsletter\","
            + "\"thread_metadata\":{\"name\":{\"text\":\"Daily\"},\"creation_time\":\"1700000000\","
            + "\"description\":{\"text\":\"Desc\"},\"invite\":\"abc123\",\"subscribers_count\":\"42\","
            + "\"picture\":{\"id\":\"pic1\",\"direct_path\":\"https://example.invalid/pic\"}},"
            + "\"viewer_metadata\":{\"mute\":\"ON\"}}}}";
        NewsletterMetadata meta = NewsletterMexQuery.parseCreateResponse(json);
        assertThat(meta).isNotNull();
        assertThat(meta.id()).isEqualTo("123@newsletter");
        assertThat(meta.name()).isEqualTo("Daily");
        assertThat(meta.description()).isEqualTo("Desc");
        assertThat(meta.inviteCode()).isEqualTo("abc123");
        assertThat(meta.creationTime()).isEqualTo(1700000000L);
        assertThat(meta.subscriberCount()).isEqualTo(42);
        assertThat(meta.muted()).isTrue();
        assertThat(meta.pictureUrl()).isEqualTo("https://example.invalid/pic");
        assertThat(meta.role()).isNull();
    }

    @Test
    void parseCreateResponseMalformedReturnsNull() {
        assertThat(NewsletterMexQuery.parseCreateResponse(null)).isNull();
        assertThat(NewsletterMexQuery.parseCreateResponse("")).isNull();
        assertThat(NewsletterMexQuery.parseCreateResponse("{\"data\":{}}")).isNull();
        assertThat(NewsletterMexQuery.parseCreateResponse("not json at all")).isNull();
    }

    @Test
    void parseAdminCountExtractsAndHandlesMissing() {
        assertThat(NewsletterMexQuery.parseAdminCount("{\"admin_count\":3}")).isEqualTo(3);
        assertThat(NewsletterMexQuery.parseAdminCount("{\"something_else\":1}")).isNull();
        assertThat(NewsletterMexQuery.parseAdminCount(null)).isNull();
        assertThat(NewsletterMexQuery.parseAdminCount("")).isNull();
    }

    @Test
    void decoderDecodesPlaintextAndSkipsOthers() {
        Wa.Message proto = Wa.Message.newBuilder().setConversation("hello channel").build();
        BinaryNode good = new BinaryNode("message",
            Map.of("id", "m1", "server_id", "7", "t", "1700000001", "type", "text"),
            List.of(new BinaryNode("plaintext", Map.of(), proto.toByteArray())));
        BinaryNode noPlaintext = new BinaryNode("message", Map.of("id", "m2"), List.of());
        BinaryNode badPlaintext = new BinaryNode("message", Map.of("id", "m3"),
            List.of(new BinaryNode("plaintext", Map.of(), new byte[] { (byte) 0xFF, 0x01, 0x02 })));
        BinaryNode messages = new BinaryNode("messages", Map.of("jid", "123@newsletter"),
            List.of(good, noPlaintext, badPlaintext));

        List<NewsletterMessage> decoded = NewsletterMessageDecoder.decodeAll(messages, "123@newsletter");
        assertThat(decoded).hasSize(1);
        assertThat(decoded.get(0).messageId()).isEqualTo("m1");
        assertThat(decoded.get(0).serverId()).isEqualTo(7L);
        assertThat(decoded.get(0).timestamp()).isEqualTo(1700000001L);
        assertThat(decoded.get(0).message().getConversation()).isEqualTo("hello channel");
        assertThat(NewsletterMessageDecoder.decodeAll(null, "123@newsletter")).isEmpty();
        assertThat(NewsletterMessageDecoder.decode(null, "123@newsletter")).isNull();
    }

    @Test
    void linkedProfilesParsesBareAndObjectEntries() {
        String json = "{\"data\":{\"xwa2_notify_linked_profiles\":{\"jid\":\"111@lid\","
            + "\"added_profiles\":[\"222@s.whatsapp.net\",{\"pn\":\"333@s.whatsapp.net\"},"
            + "{\"jid\":\"444@s.whatsapp.net\"}]}}}";
        NewsletterLinkedProfiles.LinkedProfilesUpdate update = NewsletterLinkedProfiles.parse(json);
        assertThat(update).isNotNull();
        assertThat(update.lidJid()).isEqualTo("111@lid");
        assertThat(update.addedPnJids()).containsExactly(
            "222@s.whatsapp.net", "333@s.whatsapp.net", "444@s.whatsapp.net");
    }

    @Test
    void linkedProfilesMalformedReturnsNull() {
        assertThat(NewsletterLinkedProfiles.parse(null)).isNull();
        assertThat(NewsletterLinkedProfiles.parse("")).isNull();
        assertThat(NewsletterLinkedProfiles.parse("{\"data\":{}}")).isNull();
        assertThat(NewsletterLinkedProfiles.parse("{\"data\":{\"xwa2_notify_linked_profiles\":{}}}")).isNull();
    }
}
