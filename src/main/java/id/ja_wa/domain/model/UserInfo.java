package id.ja_wa.domain.model;

import java.util.List;

/**
 * Aggregated user profile and presence information retrieved via USync queries.
 *
 * @param jid Primary phone number JID
 * @param lid Linked device identity JID, if known
 * @param status About/bio text string
 * @param pictureId Avatar file ID tag
 * @param verifiedName Official verified enterprise business name, if applicable
 * @param devices List of active companion devices associated with the account
 */
public record UserInfo(
    String jid,
    String lid,
    String status,
    String pictureId,
    String verifiedName,
    List<DeviceInfo> devices
) {}
