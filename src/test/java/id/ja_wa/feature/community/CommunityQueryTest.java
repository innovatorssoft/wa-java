package id.ja_wa.feature.community;

import id.ja_wa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommunityQueryTest {

    @Test
    void createHasBaileysShape() {
        BinaryNode iq = CommunityQuery.buildCreate("iq1", "My Community", "Welcome", "descid123456");
        assertThat(iq.tag()).isEqualTo("iq");
        assertThat(iq.attrs()).containsEntry("xmlns", "w:g2")
            .containsEntry("type", "set")
            .containsEntry("to", "g.us")
            .containsEntry("id", "iq1");
        BinaryNode create = iq.child("create");
        assertThat(create.attr("subject")).isEqualTo("My Community");
        assertThat(create.child("description").attr("id")).isEqualTo("descid123456");
        assertThat(create.child("description").child("body").textContent()).isEqualTo("Welcome");
        assertThat(create.child("parent").attr("default_membership_approval_mode")).isEqualTo("request_required");
        assertThat(create.child("allow_non_admin_sub_group_creation")).isNotNull();
        assertThat(create.child("create_general_chat")).isNotNull();
    }

    @Test
    void createGroupLinksParent() {
        BinaryNode iq = CommunityQuery.buildCreateGroup("iq1", "Sub", List.of("111@s.whatsapp.net"),
            "999@g.us", "key123");
        BinaryNode create = iq.child("create");
        assertThat(create.attr("key")).isEqualTo("key123");
        assertThat(create.child("linked_parent").attr("jid")).isEqualTo("999@g.us");
        assertThat(create.children("participant")).hasSize(1);
    }

    @Test
    void metadataAndFetchAllShapes() {
        BinaryNode meta = CommunityQuery.buildMetadata("iq1", "999@g.us");
        assertThat(meta.attr("type")).isEqualTo("get");
        assertThat(meta.attr("to")).isEqualTo("999@g.us");
        assertThat(meta.child("query").attr("request")).isEqualTo("interactive");

        BinaryNode all = CommunityQuery.buildFetchAllParticipating("iq1");
        assertThat(all.attr("to")).isEqualTo("g.us");
        assertThat(all.child("participating").child("participants")).isNotNull();
        assertThat(all.child("participating").child("description")).isNotNull();
    }

    @Test
    void updateDescriptionDeleteAndSetShapes() {
        BinaryNode del = CommunityQuery.buildUpdateDescription("iq1", "999@g.us", null, "prev1", null);
        assertThat(del.child("description").attr("delete")).isEqualTo("true");
        assertThat(del.child("description").attr("prev")).isEqualTo("prev1");

        BinaryNode set = CommunityQuery.buildUpdateDescription("iq1", "999@g.us", "New desc", null, "newid1");
        assertThat(set.child("description").attr("id")).isEqualTo("newid1");
        assertThat(set.child("description").attr("prev")).isNull();
        assertThat(set.child("description").child("body").textContent()).isEqualTo("New desc");
    }

    @Test
    void settingsModesAndEphemeral() {
        assertThat(CommunityQuery.buildSettingUpdate("iq1", "999@g.us", "locked").child("locked")).isNotNull();
        BinaryNode mode = CommunityQuery.buildMemberAddMode("iq1", "999@g.us", "all_member_add");
        assertThat(mode.child("member_add_mode").textContent()).isEqualTo("all_member_add");
        BinaryNode approval = CommunityQuery.buildJoinApprovalMode("iq1", "999@g.us", "request_required");
        assertThat(approval.child("membership_approval_mode").child("community_join").attr("state"))
            .isEqualTo("request_required");
        assertThat(CommunityQuery.buildToggleEphemeral("iq1", "999@g.us", null).child("not_ephemeral")).isNotNull();
        assertThat(CommunityQuery.buildToggleEphemeral("iq1", "999@g.us", 86400L)
            .child("ephemeral").attr("expiration")).isEqualTo("86400");
    }

    @Test
    void removeParticipantsCarriesLinkedGroupsFlag() {
        BinaryNode remove = CommunityQuery.buildParticipantsUpdate("iq1", "999@g.us",
            List.of("111@s.whatsapp.net"), "remove");
        assertThat(remove.child("remove").attr("linked_groups")).isEqualTo("true");
        BinaryNode add = CommunityQuery.buildParticipantsUpdate("iq1", "999@g.us",
            List.of("111@s.whatsapp.net"), "add");
        assertThat(add.child("add").attr("linked_groups")).isNull();
    }

    @Test
    void inviteShapes() {
        assertThat(CommunityQuery.buildInviteCode("iq1", "999@g.us").attr("type")).isEqualTo("get");
        assertThat(CommunityQuery.buildRevokeInvite("iq1", "999@g.us").attr("type")).isEqualTo("set");
        BinaryNode accept = CommunityQuery.buildAcceptInvite("iq1", "code123");
        assertThat(accept.attr("to")).isEqualTo("g.us");
        assertThat(accept.child("invite").attr("code")).isEqualTo("code123");
        assertThat(CommunityQuery.buildGetInviteInfo("iq1", "code123").attr("type")).isEqualTo("get");
    }

    @Test
    void blankInputsRejected() {
        assertThatThrownBy(() -> CommunityQuery.buildCreate("", "S", "D", "descid"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommunityQuery.buildSettingUpdate("iq1", "999@g.us", ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommunityQuery.buildUpdateDescription("iq1", "999@g.us", "D", null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseLinkedGroupsMapsBaileysFields() {
        BinaryNode result = new BinaryNode("iq", Map.of(), List.of(
            new BinaryNode("sub_groups", Map.of(), List.of(
                new BinaryNode("group", Map.of("id", "111", "subject", "Sub",
                    "creation", "1700000000", "creator", "999@s.whatsapp.net", "size", "5"), null),
                new BinaryNode("group", Map.of("subject", "No id"), null)))));
        List<CommunityQuery.LinkedGroup> groups = CommunityQuery.parseLinkedGroups(result);
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).id()).isEqualTo("111@g.us");
        assertThat(groups.get(0).subject()).isEqualTo("Sub");
        assertThat(groups.get(0).creation()).isEqualTo(1700000000L);
        assertThat(groups.get(0).owner()).isEqualTo("999@s.whatsapp.net");
        assertThat(groups.get(0).size()).isEqualTo(5L);
        assertThat(CommunityQuery.parseLinkedGroups(null)).isEmpty();
        assertThat(CommunityQuery.parseLinkedGroups(new BinaryNode("iq", Map.of(), List.of()))).isEmpty();
    }

    @Test
    void parseParticipantsResultDefaultsMissingErrorTo200() {
        BinaryNode result = new BinaryNode("iq", Map.of(), List.of(
            new BinaryNode("add", Map.of(), List.of(
                new BinaryNode("participant", Map.of("jid", "111@s.whatsapp.net"), null),
                new BinaryNode("participant", Map.of("jid", "222@s.whatsapp.net", "error", "403"), null)))));
        List<CommunityQuery.ParticipantResult> results = CommunityQuery.parseParticipantsResult(result, "add");
        assertThat(results).containsExactly(
            new CommunityQuery.ParticipantResult("111@s.whatsapp.net", "200"),
            new CommunityQuery.ParticipantResult("222@s.whatsapp.net", "403"));
        assertThat(CommunityQuery.parseParticipantsResult(null, "add")).isEmpty();
        assertThat(CommunityQuery.parseParticipantsResult(result, "remove")).isEmpty();
    }

    @Test
    void parseInviteHelpers() {
        BinaryNode result = new BinaryNode("iq", Map.of(), List.of(
            new BinaryNode("invite", Map.of("code", "abc"), null),
            new BinaryNode("community", Map.of("jid", "999@g.us"), null)));
        assertThat(CommunityQuery.parseInviteCode(result)).isEqualTo("abc");
        assertThat(CommunityQuery.parseAcceptInviteCommunityJid(result)).isEqualTo("999@g.us");
        assertThat(CommunityQuery.parseInviteCode(null)).isNull();
    }
}
