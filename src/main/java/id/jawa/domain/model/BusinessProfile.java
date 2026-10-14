package id.jawa.domain.model;

import java.util.List;
import java.util.Map;

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
    public record Category(String id, String name) {}
    public record BusinessHourConfig(String dayOfWeek, String mode, String openTime, String closeTime) {}
}
