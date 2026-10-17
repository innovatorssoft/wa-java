package id.jawa.domain.model;

import java.util.List;

/**
 * Privacy configuration for WhatsApp status updates (stories).
 *
 * @param type The privacy rule type ("contacts", "whitelist", or "blacklist")
 * @param isDefault Whether this rule represents the user default status privacy policy
 * @param list List of participant JIDs included in the whitelist or blacklist
 */
public record StatusPrivacy(
    String type,
    boolean isDefault,
    List<String> list
) {}
