package id.jawa.event;

import id.jawa.proto.Wa;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppStateSettingsLockMapperTest {

    @Test
    void lockChatMapsToChatsLock() {
        Wa.SyncActionValue value = Wa.SyncActionValue.newBuilder()
            .setLockChatAction(Wa.SyncActionValue.LockChatAction.newBuilder().setLocked(true))
            .build();
        Object mapped = AppStateSettingsLockMapper.map(value, "111@s.whatsapp.net");
        assertThat(mapped).isEqualTo(new ChatsLock("111@s.whatsapp.net", true));
        assertThat(AppStateSettingsLockMapper.chatsLock(value, "111@s.whatsapp.net").locked()).isTrue();
        assertThat(AppStateSettingsLockMapper.settingsUpdate(value)).isNull();
    }

    @Test
    void lockChatAbsentLockedDefaultsFalse() {
        Wa.SyncActionValue value = Wa.SyncActionValue.newBuilder()
            .setLockChatAction(Wa.SyncActionValue.LockChatAction.newBuilder().build())
            .build();
        assertThat(AppStateSettingsLockMapper.chatsLock(value, "111@s.whatsapp.net").locked()).isFalse();
    }

    @Test
    void localeMapsToStringValueAndEmptyLocaleMapsToNothing() {
        Wa.SyncActionValue value = Wa.SyncActionValue.newBuilder()
            .setLocaleSetting(Wa.SyncActionValue.LocaleSetting.newBuilder().setLocale("en-GB"))
            .build();
        assertThat(AppStateSettingsLockMapper.settingsUpdate(value))
            .isEqualTo(new SettingsUpdate("locale", "en-GB"));

        Wa.SyncActionValue empty = Wa.SyncActionValue.newBuilder()
            .setLocaleSetting(Wa.SyncActionValue.LocaleSetting.newBuilder().build())
            .build();
        assertThat(AppStateSettingsLockMapper.map(empty, "111@s.whatsapp.net")).isNull();
    }

    @Test
    void otherSettingsBranchesPassProtoValuesThrough() {
        Wa.SyncActionValue relay = Wa.SyncActionValue.newBuilder()
            .setPrivacySettingRelayAllCalls(
                Wa.SyncActionValue.PrivacySettingRelayAllCalls.newBuilder().setIsEnabled(true))
            .build();
        SettingsUpdate relayUpdate = AppStateSettingsLockMapper.settingsUpdate(relay);
        assertThat(relayUpdate.setting()).isEqualTo("privacySettingRelayAllCalls");
        assertThat(relayUpdate.value()).isEqualTo(relay.getPrivacySettingRelayAllCalls());

        Wa.SyncActionValue timeFormat = Wa.SyncActionValue.newBuilder()
            .setTimeFormatAction(
                Wa.SyncActionValue.TimeFormatAction.newBuilder().setIsTwentyFourHourFormatEnabled(true))
            .build();
        assertThat(AppStateSettingsLockMapper.settingsUpdate(timeFormat).setting()).isEqualTo("timeFormat");

        Wa.SyncActionValue statusPrivacy = Wa.SyncActionValue.newBuilder()
            .setStatusPrivacy(Wa.SyncActionValue.StatusPrivacyAction.newBuilder()
                .setMode(Wa.SyncActionValue.StatusPrivacyAction.StatusDistributionMode.CONTACTS))
            .build();
        assertThat(AppStateSettingsLockMapper.settingsUpdate(statusPrivacy).setting())
            .isEqualTo("statusPrivacy");

        Wa.SyncActionValue disablePreviews = Wa.SyncActionValue.newBuilder()
            .setPrivacySettingDisableLinkPreviewsAction(
                Wa.SyncActionValue.PrivacySettingDisableLinkPreviewsAction.newBuilder()
                    .setIsPreviewsDisabled(true))
            .build();
        assertThat(AppStateSettingsLockMapper.settingsUpdate(disablePreviews).setting())
            .isEqualTo("disableLinkPreviews");

        Wa.SyncActionValue notification = Wa.SyncActionValue.newBuilder()
            .setNotificationActivitySettingAction(
                Wa.SyncActionValue.NotificationActivitySettingAction.newBuilder()
                    .setNotificationActivitySetting(
                        Wa.SyncActionValue.NotificationActivitySettingAction
                            .NotificationActivitySetting.HIGHLIGHTS))
            .build();
        SettingsUpdate notificationUpdate = AppStateSettingsLockMapper.settingsUpdate(notification);
        assertThat(notificationUpdate.setting()).isEqualTo("notificationActivitySetting");
        assertThat(notificationUpdate.value()).isEqualTo(
            Wa.SyncActionValue.NotificationActivitySettingAction.NotificationActivitySetting.HIGHLIGHTS);

        Wa.SyncActionValue channels = Wa.SyncActionValue.newBuilder()
            .setPrivacySettingChannelsPersonalisedRecommendationAction(
                Wa.SyncActionValue.PrivacySettingChannelsPersonalisedRecommendationAction.newBuilder()
                    .setIsUserOptedOut(true))
            .build();
        assertThat(AppStateSettingsLockMapper.settingsUpdate(channels).setting())
            .isEqualTo("channelsPersonalisedRecommendation");
    }

    @Test
    void unownedBranchesAndNullYieldNull() {
        assertThat(AppStateSettingsLockMapper.map(null, "111@s.whatsapp.net")).isNull();
        assertThat(AppStateSettingsLockMapper.map(Wa.SyncActionValue.newBuilder().build(),
            "111@s.whatsapp.net")).isNull();
        Wa.SyncActionValue mute = Wa.SyncActionValue.newBuilder()
            .setMuteAction(Wa.SyncActionValue.MuteAction.newBuilder().setMuted(true))
            .build();
        assertThat(AppStateSettingsLockMapper.map(mute, "111@s.whatsapp.net")).isNull();
    }

    @Test
    void messageCappingKeepsRawPayloadOnly() {
        MessageCappingUpdate update = new MessageCappingUpdate("{\"capped\":true}");
        assertThat(update.rawJson()).isEqualTo("{\"capped\":true}");
    }
}
