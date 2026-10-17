package id.jawa.domain.model;

import java.util.Map;

/**
 * Account privacy settings covering visibility of personal information and communications.
 *
 * @param groupAdd Setting controlling who can add the user to groups
 * @param lastSeen Setting controlling visibility of last seen status
 * @param status Setting controlling visibility of user status/stories
 * @param profilePhoto Setting controlling visibility of profile picture
 * @param readReceipts Setting controlling whether read receipts (blue ticks) are sent
 * @param online Setting controlling online presence visibility
 * @param callAdd Setting controlling call addition permissions
 * @param rawCategories Map of all raw category keys and values returned by the server
 */
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
