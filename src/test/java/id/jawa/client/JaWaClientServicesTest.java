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
}
