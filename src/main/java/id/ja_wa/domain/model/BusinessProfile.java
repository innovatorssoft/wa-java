package id.ja_wa.domain.model;

import java.util.List;
import java.util.Map;

/**
 * Public profile information for WhatsApp Business accounts.
 *
 * @param jid Business user JID
 * @param address Physical street or store address
 * @param email Customer support contact email
 * @param description Detailed business bio or description
 * @param websites List of company website URLs
 * @param categories Assigned trade or industry categories
 * @param businessHoursTimeZone Timezone ID for operating schedule
 * @param businessHours Weekly opening hours configuration
 * @param profileOptions Additional vendor profile flags and attributes
 */
public record BusinessProfile(
    String jid,
    String address,
    String email,
    String description,
    List<String> websites,
    List<Category> categories,
    String businessHoursTimeZone,
    List<BusinessHourConfig> businessHours,
    Map<String, String> profileOptions
) {
    /**
     * Industry category entry.
     *
     * @param id Category unique identifier
     * @param name Category display name
     */
    public record Category(String id, String name) {}

    /**
     * Operating hour configuration for a specific weekday.
     *
     * @param dayOfWeek Name of the day (e.g. "mon", "tue")
     * @param mode Operational mode ("open_24h", "appointment_only", "specific_hours")
     * @param openTime Opening time string (e.g. "09:00")
     * @param closeTime Closing time string (e.g. "17:00")
     */
    public record BusinessHourConfig(String dayOfWeek, String mode, String openTime, String closeTime) {}
}
