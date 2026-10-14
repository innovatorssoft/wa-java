package id.jawa.domain.model;

import java.util.Map;

public record PrivacySettings(
    String groupAdd,
    String lastSeen,
    String status,
    String profilePhoto,
    String readReceipts,
    String online,
    String callAdd,
    Map<String, String> rawCategories
) {}
