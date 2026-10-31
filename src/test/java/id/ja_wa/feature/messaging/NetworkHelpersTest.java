package id.ja_wa.feature.messaging;

import id.ja_wa.protocol.codec.BinaryNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkHelpersTest {

    private static BinaryNode groupNode() {
        return new BinaryNode("group",
            Map.of("id", "123@g.us", "subject", "Team", "creator", "999@s.whatsapp.net",
                "creation", "1700000000", "s_t", "1700000100", "addressing_mode", "lid"),
            List.of(
                new BinaryNode("participant",
                    Map.of("jid", "111@lid", "phone_number", "111@s.whatsapp.net",
                        "type", "admin"),
                    null),
                new BinaryNode("participant", Map.of("jid", "222@s.whatsapp.net"), null),
                new BinaryNode("description", Map.of("id", "d1", "participant", "999@s.whatsapp.net"),
                    List.of(new BinaryNode("body", Map.of(), "Hello team"))),
                new BinaryNode("ephemeral", Map.of("expiration", "86400"), null),
                new BinaryNode("announcement", Map.of(), null)));
    }

    @Test
    void groupMetadataQueryBuildsBaileysShape() {
        BinaryNode q = GroupMetadataQuery.buildQuery("iq1", "123@g.us");
        assertThat(q.tag()).isEqualTo("iq");
        assertThat(q.attr("type")).isEqualTo("get");
        assertThat(q.attr("xmlns")).isEqualTo("w:g2");
        assertThat(q.attr("to")).isEqualTo("123@g.us");
        BinaryNode query = q.child("query");
        assertThat(query).isNotNull();
        assertThat(query.attr("request")).isEqualTo("interactive");
    }

    @Test
    void groupMetadataParseExtractsFullMetadata() {
        BinaryNode result = new BinaryNode("iq", Map.of(), List.of(groupNode()));
        GroupMetadataQuery.GroupMetadata meta = GroupMetadataQuery.parse(result);
        assertThat(meta.id()).isEqualTo("123@g.us");
        assertThat(meta.notifyAttr()).isNull();
        assertThat(meta.subject()).isEqualTo("Team");
        assertThat(meta.owner()).isEqualTo("999@s.whatsapp.net");
        assertThat(meta.addressingMode()).isEqualTo("lid");
        assertThat(meta.creation()).isEqualTo(1700000000L);
        assertThat(meta.subjectTime()).isEqualTo(1700000100L);
        assertThat(meta.size()).isEqualTo(2);
        assertThat(meta.desc()).isEqualTo("Hello team");
        assertThat(meta.descId()).isEqualTo("d1");
        assertThat(meta.descOwner()).isEqualTo("999@s.whatsapp.net");
        assertThat(meta.ephemeralDuration()).isEqualTo(86400);
        assertThat(meta.announce()).isTrue();
        assertThat(meta.restrict()).isFalse();
        assertThat(meta.participants()).hasSize(2);
        assertThat(meta.participants().get(0).id()).isEqualTo("111@lid");
        assertThat(meta.participants().get(0).phoneNumber()).isEqualTo("111@s.whatsapp.net");
        assertThat(meta.participants().get(0).admin()).isEqualTo("admin");
        assertThat(meta.participants().get(1).phoneNumber()).isNull();
    }

    @Test
    void groupMetadataParseBareIdBecomesGroupJidAndErrorsSurface() {
        BinaryNode bare = new BinaryNode("group", Map.of("id", "123"), List.of());
        assertThat(GroupMetadataQuery.parse(new BinaryNode("iq", Map.of(), List.of(bare))).id())
            .isEqualTo("123@g.us");

        BinaryNode error = new BinaryNode("iq", Map.of(),
            List.of(new BinaryNode("error", Map.of("code", "404", "text", "not found"), null)));
        assertThatThrownBy(() -> GroupMetadataQuery.parse(error))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("404")
            .hasMessageContaining("not found");
        assertThatThrownBy(() -> GroupMetadataQuery.parse(new BinaryNode("iq", Map.of(), List.of())))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void groupMetadataParticipatingParsesGroupsChild() {
        BinaryNode result = new BinaryNode("iq", Map.of(),
            List.of(new BinaryNode("groups", Map.of(), List.of(groupNode()))));
        List<GroupMetadataQuery.GroupMetadata> metas = GroupMetadataQuery.parseParticipating(result);
        assertThat(metas).hasSize(1);
        assertThat(metas.get(0).subject()).isEqualTo("Team");
        assertThat(GroupMetadataQuery.parseParticipating(
            new BinaryNode("iq", Map.of(), List.of()))).isEmpty();
    }

    @Test
    void profilePictureQueryBuildsBaileysShapeAndParsesUrl() {
        BinaryNode q = ProfilePictureQuery.buildQuery("iq2", "111:5@s.whatsapp.net", false);
        assertThat(q.tag()).isEqualTo("iq");
        assertThat(q.attr("to")).isEqualTo("s.whatsapp.net");
        assertThat(q.attr("target")).isEqualTo("111@s.whatsapp.net");
        assertThat(q.attr("xmlns")).isEqualTo("w:profile:picture");
        BinaryNode picture = q.child("picture");
        assertThat(picture.attr("type")).isEqualTo("image");
        assertThat(picture.attr("query")).isEqualTo("url");

        BinaryNode preview = ProfilePictureQuery.buildQuery("iq3", "111@s.whatsapp.net", true);
        assertThat(preview.child("picture").attr("type")).isEqualTo("preview");

        BinaryNode response = new BinaryNode("iq", Map.of("type", "result"),
            List.of(new BinaryNode("picture",
                Map.of("url", "https://example.invalid/p.jpg", "id", "pic1", "type", "image"),
                null)));
        assertThat(ProfilePictureQuery.parse(response).url()).isEqualTo("https://example.invalid/p.jpg");
        assertThat(ProfilePictureQuery.parse(response).id()).isEqualTo("pic1");
    }

    @Test
    void profilePictureParseRejectsErrorsAndEmptyPictures() {
        assertThat(ProfilePictureQuery.parse(null)).isNull();
        assertThat(ProfilePictureQuery.parse(
            new BinaryNode("iq", Map.of("type", "error"), List.of()))).isNull();
        BinaryNode noPic = new BinaryNode("iq", Map.of(),
            List.of(new BinaryNode("picture", Map.of("status", "204"), null)));
        assertThat(ProfilePictureQuery.parse(noPic)).isNull();
        BinaryNode noUrl = new BinaryNode("iq", Map.of(),
            List.of(new BinaryNode("picture", Map.of(), null)));
        assertThat(ProfilePictureQuery.parse(noUrl)).isNull();
        assertThatThrownBy(() -> ProfilePictureQuery.buildQuery("iq", "  ", false))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
