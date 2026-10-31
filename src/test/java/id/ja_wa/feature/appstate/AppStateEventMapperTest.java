package id.ja_wa.feature.appstate;

import com.google.protobuf.ByteString;
import id.ja_wa.event.BaileysEventType;
import id.ja_wa.event.ChatsLock;
import id.ja_wa.event.LabelsAssociation;
import id.ja_wa.event.LabelsEdit;
import id.ja_wa.event.MessagingHistorySet;
import id.ja_wa.event.MessagingHistoryStatus;
import id.ja_wa.event.SettingsUpdate;
import id.ja_wa.proto.Wa;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppStateEventMapperTest {

    private static Wa.SyncActionData action(String indexJson, Wa.SyncActionValue value) {
        Wa.SyncActionData.Builder builder = Wa.SyncActionData.newBuilder();
        if (indexJson != null) builder.setIndex(ByteString.copyFromUtf8(indexJson));
        if (value != null) builder.setValue(value);
        return builder.build();
    }

    @Test
    void labelEditMapsWithPredefinedIdStringified() {
        Wa.SyncActionValue value = Wa.SyncActionValue.newBuilder()
            .setLabelEditAction(Wa.SyncActionValue.LabelEditAction.newBuilder()
                .setName("Work").setColor(3).setPredefinedId(7).setDeleted(false))
            .build();
        List<AppStateEventMapper.MappedEvent> events = AppStateEventMapper.map(
            action("[\"label_edit\",\"42\"]", value));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo(BaileysEventType.LABELS_EDIT);
        LabelsEdit edit = (LabelsEdit) events.get(0).payload();
        assertThat(edit.id()).isEqualTo("42");
        assertThat(edit.name()).isEqualTo("Work");
        assertThat(edit.color()).isEqualTo("3");
        assertThat(edit.deleted()).isFalse();
        assertThat(edit.predefinedId()).isEqualTo("7");
    }

    @Test
    void labelEditDropsZeroPredefinedId() {
        Wa.SyncActionValue value = Wa.SyncActionValue.newBuilder()
            .setLabelEditAction(Wa.SyncActionValue.LabelEditAction.newBuilder()
                .setName("Work").setColor(1).setPredefinedId(0).setDeleted(true))
            .build();
        LabelsEdit edit = (LabelsEdit) AppStateEventMapper.map(
            action("[\"label_edit\",\"42\"]", value)).get(0).payload();
        assertThat(edit.predefinedId()).isNull();
        assertThat(edit.deleted()).isTrue();
    }

    @Test
    void labelAssociationChatAndMessageVariants() {
        Wa.SyncActionValue labeled = Wa.SyncActionValue.newBuilder()
            .setLabelAssociationAction(Wa.SyncActionValue.LabelAssociationAction.newBuilder()
                .setLabeled(true))
            .build();
        LabelsAssociation chat = (LabelsAssociation) AppStateEventMapper.map(
            action("[\"label_jid\",\"42\",\"111@s.whatsapp.net\"]", labeled)).get(0).payload();
        assertThat(chat.type()).isEqualTo("add");
        assertThat(chat.associationType()).isEqualTo("label_jid");
        assertThat(chat.chatId()).isEqualTo("111@s.whatsapp.net");
        assertThat(chat.labelId()).isEqualTo("42");
        assertThat(chat.messageId()).isNull();

        Wa.SyncActionValue unlabeled = Wa.SyncActionValue.newBuilder()
            .setLabelAssociationAction(Wa.SyncActionValue.LabelAssociationAction.newBuilder()
                .setLabeled(false))
            .build();
        LabelsAssociation msg = (LabelsAssociation) AppStateEventMapper.map(
            action("[\"label_message\",\"42\",\"111@s.whatsapp.net\",\"msg1\",\"0\",\"0\"]", unlabeled))
            .get(0).payload();
        assertThat(msg.type()).isEqualTo("remove");
        assertThat(msg.associationType()).isEqualTo("label_message");
        assertThat(msg.messageId()).isEqualTo("msg1");
    }

    @Test
    void settingsAndLockMappings() {
        Wa.SyncActionValue locale = Wa.SyncActionValue.newBuilder()
            .setLocaleSetting(Wa.SyncActionValue.LocaleSetting.newBuilder().setLocale("en"))
            .build();
        SettingsUpdate localeUpdate = (SettingsUpdate) AppStateEventMapper.map(
            action("[\"setting_locale\"]", locale)).get(0).payload();
        assertThat(localeUpdate.setting()).isEqualTo("locale");
        assertThat(localeUpdate.value()).isEqualTo("en");

        Wa.SyncActionValue lock = Wa.SyncActionValue.newBuilder()
            .setLockChatAction(Wa.SyncActionValue.LockChatAction.newBuilder().setLocked(true))
            .build();
        ChatsLock chatsLock = (ChatsLock) AppStateEventMapper.map(
            action("[\"lockChat\",\"111@s.whatsapp.net\"]", lock)).get(0).payload();
        assertThat(chatsLock.id()).isEqualTo("111@s.whatsapp.net");
        assertThat(chatsLock.locked()).isTrue();

        Wa.SyncActionValue timeFormat = Wa.SyncActionValue.newBuilder()
            .setTimeFormatAction(Wa.SyncActionValue.TimeFormatAction.newBuilder()
                .setIsTwentyFourHourFormatEnabled(true))
            .build();
        SettingsUpdate tf = (SettingsUpdate) AppStateEventMapper.map(
            action("[\"setting_timeFormat\"]", timeFormat)).get(0).payload();
        assertThat(tf.setting()).isEqualTo("timeFormat");
        assertThat(((Wa.SyncActionValue.TimeFormatAction) tf.value()).getIsTwentyFourHourFormatEnabled())
            .isTrue();
    }

    @Test
    void malformedOrUnmappedActionsYieldEmptyList() {
        assertThat(AppStateEventMapper.map(null)).isEmpty();
        assertThat(AppStateEventMapper.map(Wa.SyncActionData.newBuilder().build())).isEmpty();
        Wa.SyncActionValue mute = Wa.SyncActionValue.newBuilder()
            .setMuteAction(Wa.SyncActionValue.MuteAction.newBuilder().setMuted(true))
            .build();
        // Mute is a real sync action but outside this mapper's scoped events.
        assertThat(AppStateEventMapper.map(action("[\"mute\",\"111@s.whatsapp.net\"]", mute))).isEmpty();
        Wa.SyncActionValue label = Wa.SyncActionValue.newBuilder()
            .setLabelEditAction(Wa.SyncActionValue.LabelEditAction.newBuilder().setName("x"))
            .build();
        assertThat(AppStateEventMapper.map(action("not-json", label))).isEmpty();
        assertThat(AppStateEventMapper.map(action("[\"label_edit\"]", label))).isEmpty();
        assertThat(AppStateEventMapper.mapMutation(null)).isEmpty();
    }

    @Test
    void historySetMapsConversationsAndPushNames() {
        Wa.WebMessageInfo info = Wa.WebMessageInfo.newBuilder()
            .setKey(Wa.MessageKey.newBuilder()
                .setRemoteJid("111@s.whatsapp.net").setId("msg1").build())
            .setMessage(Wa.Message.newBuilder().setConversation("hi").build())
            .setMessageTimestamp(1700000001L)
            .build();
        Wa.HistorySync history = Wa.HistorySync.newBuilder()
            .setSyncType(Wa.HistorySync.HistorySyncType.RECENT)
            .setProgress(50)
            .setChunkOrder(2)
            .addConversations(Wa.Conversation.newBuilder()
                .setId("111@s.whatsapp.net")
                .addMessages(Wa.HistorySyncMsg.newBuilder().setMessage(info).build())
                .build())
            .build();
        AppStateEventMapper.MappedEvent event =
            AppStateEventMapper.mapHistorySet(history, true, "session-1");
        assertThat(event.type()).isEqualTo(BaileysEventType.MESSAGING_HISTORY_SET);
        MessagingHistorySet set = (MessagingHistorySet) event.payload();
        assertThat(set.chatIds()).containsExactly("111@s.whatsapp.net");
        assertThat(set.contactIds()).containsExactly("111@s.whatsapp.net");
        assertThat(set.syncType()).isEqualTo("RECENT");
        assertThat(set.progress()).isEqualTo(50L);
        assertThat(set.chunkOrder()).isEqualTo(2);
        assertThat(set.isLatest()).isTrue();
        assertThat(set.peerDataRequestSessionId()).isEqualTo("session-1");
        assertThat(set.messages()).hasSize(1);
        assertThat(set.messages().get(0).msgId()).isEqualTo("msg1");
        assertThat(set.messages().get(0).text()).isEqualTo("hi");
        assertThat(set.messages().get(0).timestamp()).isEqualTo(1700000001L);

        Wa.HistorySync onDemand = Wa.HistorySync.newBuilder()
            .setSyncType(Wa.HistorySync.HistorySyncType.ON_DEMAND)
            .build();
        MessagingHistorySet onDemandSet =
            (MessagingHistorySet) AppStateEventMapper.mapHistorySet(onDemand, true, null).payload();
        assertThat(onDemandSet.isLatest()).isNull();

        Wa.HistorySync pushNames = Wa.HistorySync.newBuilder()
            .setSyncType(Wa.HistorySync.HistorySyncType.PUSH_NAME)
            .addPushnames(Wa.Pushname.newBuilder().setId("222@s.whatsapp.net").build())
            .build();
        MessagingHistorySet pushSet =
            (MessagingHistorySet) AppStateEventMapper.mapHistorySet(pushNames, false, null).payload();
        assertThat(pushSet.contactIds()).containsExactly("222@s.whatsapp.net");
        assertThat(pushSet.chatIds()).isEmpty();
    }

    @Test
    void historyStatusMapsOnlyExplicitCompletions() {
        MessagingHistoryStatus bootstrap = AppStateEventMapper.historyStatusForNotification(
            Wa.HistorySync.HistorySyncType.INITIAL_BOOTSTRAP, null);
        assertThat(bootstrap).isEqualTo(new MessagingHistoryStatus("INITIAL_BOOTSTRAP", "complete", true));
        MessagingHistoryStatus recent = AppStateEventMapper.historyStatusForNotification(
            Wa.HistorySync.HistorySyncType.RECENT, 100);
        assertThat(recent).isEqualTo(new MessagingHistoryStatus("RECENT", "complete", true));
        assertThat(AppStateEventMapper.historyStatusForNotification(
            Wa.HistorySync.HistorySyncType.RECENT, 50)).isNull();
        assertThat(AppStateEventMapper.historyStatusForNotification(
            Wa.HistorySync.HistorySyncType.FULL, 100)).isNull();
        assertThat(AppStateEventMapper.historyStatusForNotification(null, 100)).isNull();
    }

    @Test
    void indexParserHandlesEscapesAndRejectsMalformed() {
        Wa.SyncActionData ok = action("[\"label_jid\",\"42\",\"a\\\"b@s.whatsapp.net\"]", null);
        assertThat(AppStateEventMapper.parseIndex(ok)).containsExactly(
            "label_jid", "42", "a\"b@s.whatsapp.net");
        assertThat(AppStateEventMapper.parseIndex(action("[\"a\",]", null))).isEmpty();
        assertThat(AppStateEventMapper.parseIndex(action("[]", null))).isEmpty();
        Wa.SyncActionData noIndex = Wa.SyncActionData.newBuilder().build();
        assertThat(AppStateEventMapper.parseIndex(noIndex)).isEmpty();
        // parseIndex is package-visible for tests; raw bytes are UTF-8 JSON by Baileys' contract.
        assertThat(StandardCharsets.UTF_8.name()).isEqualTo("UTF-8");
    }
}
