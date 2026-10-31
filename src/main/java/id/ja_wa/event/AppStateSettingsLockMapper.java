package id.ja_wa.event;

import id.ja_wa.proto.Wa;

/**
 * Pure mapper from decoded app-state sync actions to Baileys
 * {@code chats.lock} / {@code settings.update} events, following
 * {@code processSyncAction} in Baileys'
 * {@code lib/Utils/chat-utils.js}.
 * <p>
 * This mapper performs no I/O and no dispatch: the caller (JaWaClient
 * app-state handling, once wired) decodes a {@code SyncActionData} via
 * {@code AppStateProcessor}, takes the sync-action index's chat id
 * (Baileys destructures {@code index: [type, id, ...]} — {@code id} is
 * the chat id used for {@code chats.lock}) and the decoded
 * {@code SyncActionValue}, and calls {@link #map(Wa.SyncActionValue, String)}.
 * <p>
 * Baileys' branch order (only the branches this mapper owns are listed;
 * the others — mute, archive, pin, label, lid-contact, pn-for-lid —
 * are handled by other wa-java paths or not at all, and this mapper
 * returns null for them rather than misattributing them):
 * {@code localeSetting} → {@code settings.update {locale}},
 * {@code timeFormatAction} → {@code settings.update {timeFormat}},
 * {@code privacySettingRelayAllCalls} →
 * {@code settings.update {privacySettingRelayAllCalls}},
 * {@code statusPrivacy} → {@code settings.update {statusPrivacy}},
 * {@code lockChatAction} → {@code chats.lock {id, locked}},
 * {@code privacySettingDisableLinkPreviewsAction} →
 * {@code settings.update {disableLinkPreviews}},
 * {@code notificationActivitySettingAction} →
 * {@code settings.update {notificationActivitySetting}},
 * {@code privacySettingChannelsPersonalisedRecommendationAction} →
 * {@code settings.update {channelsPersonalisedRecommendation}}.
 * Baileys passes each action's proto value through unchecked
 * ({@code SettingsUpdate} likewise carries {@code Object}), except
 * {@code locale}, where it passes the locale string itself, and
 * {@code notificationActivitySetting}, where it passes the nested enum
 * value. The {@code locale} branch only fires for a non-empty locale,
 * and the {@code notificationActivitySetting} branch only when the
 * nested setting is present — matching Baileys' truthiness guards.
 * <p>
 * {@code message-capping.update} is intentionally <em>not</em> mapped
 * here: Baileys emits it from a mex notification
 * ({@code handleMessageCappingNotification} in
 * {@code lib/Socket/messages-recv.js}, emitting
 * {@code data.xwa2_notify_new_chat_messages_capping_info_update}
 * verbatim), not from a sync action, and no field list is derivable
 * from the Baileys source tree. {@link MessageCappingUpdate} therefore
 * keeps carrying only the raw JSON string (see its Javadoc); the
 * caller that receives the mex payload wraps it with
 * {@code new MessageCappingUpdate(rawJson)} and dispatches it.
 */
public final class AppStateSettingsLockMapper {

    private AppStateSettingsLockMapper() {}

    /**
     * Maps one decoded sync-action value to its Baileys event payload,
     * or null when the value carries none of the branches this mapper
     * owns (or a required guard fails, e.g. an empty locale).
     *
     * @param value decoded sync-action value; null yields null
     * @param chatId chat id from the sync-action index (only used for
     *               {@code chats.lock}; may be null for settings-only values)
     * @return a {@link ChatsLock} or {@link SettingsUpdate}, or null
     */
    public static Object map(Wa.SyncActionValue value, String chatId) {
        if (value == null) return null;
        if (value.hasLocaleSetting() && !value.getLocaleSetting().getLocale().isEmpty()) {
            return new SettingsUpdate("locale", value.getLocaleSetting().getLocale());
        }
        if (value.hasTimeFormatAction()) {
            return new SettingsUpdate("timeFormat", value.getTimeFormatAction());
        }
        if (value.hasPrivacySettingRelayAllCalls()) {
            return new SettingsUpdate("privacySettingRelayAllCalls",
                value.getPrivacySettingRelayAllCalls());
        }
        if (value.hasStatusPrivacy()) {
            return new SettingsUpdate("statusPrivacy", value.getStatusPrivacy());
        }
        if (value.hasLockChatAction()) {
            return new ChatsLock(chatId, value.getLockChatAction().getLocked());
        }
        if (value.hasPrivacySettingDisableLinkPreviewsAction()) {
            return new SettingsUpdate("disableLinkPreviews",
                value.getPrivacySettingDisableLinkPreviewsAction());
        }
        if (value.hasNotificationActivitySettingAction()
                && value.getNotificationActivitySettingAction().hasNotificationActivitySetting()) {
            return new SettingsUpdate("notificationActivitySetting",
                value.getNotificationActivitySettingAction().getNotificationActivitySetting());
        }
        if (value.hasPrivacySettingChannelsPersonalisedRecommendationAction()) {
            return new SettingsUpdate("channelsPersonalisedRecommendation",
                value.getPrivacySettingChannelsPersonalisedRecommendationAction());
        }
        return null;
    }

    /**
     * Maps only the {@code chats.lock} branch, or null.
     *
     * @param value decoded sync-action value; null yields null
     * @param chatId chat id from the sync-action index
     * @return {@link ChatsLock}, or null when the value has no lock action
     */
    public static ChatsLock chatsLock(Wa.SyncActionValue value, String chatId) {
        if (value != null && value.hasLockChatAction()) {
            return new ChatsLock(chatId, value.getLockChatAction().getLocked());
        }
        return null;
    }

    /**
     * Maps only the {@code settings.update} branches, or null.
     *
     * @param value decoded sync-action value; null yields null
     * @return {@link SettingsUpdate}, or null when the value has no
     *         settings branch this mapper owns
     */
    public static SettingsUpdate settingsUpdate(Wa.SyncActionValue value) {
        Object mapped = map(value, null);
        return mapped instanceof SettingsUpdate s ? s : null;
    }
}
