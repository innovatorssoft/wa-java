package id.jawa.event;

/**
 * Payload for Baileys {@code settings.update}.
 * <p>
 * Baileys shape: {@code {setting: string, value: any}}; observed settings:
 * {@code locale}, {@code timeFormat}, {@code privacySettingRelayAllCalls},
 * {@code statusPrivacy}, {@code disableLinkPreviews},
 * {@code notificationActivitySetting},
 * {@code channelsPersonalisedRecommendation}. The value is kept as
 * {@code Object} because Baileys passes it through unchecked.
 * <p>
 * Defined for API completeness; not yet dispatched by JaWaClient (see EVENT_COMPATIBILITY_MATRIX.md).
 *
 * @param setting setting name string
 * @param value setting value (type varies by setting)
 */
public record SettingsUpdate(String setting, Object value) {}
