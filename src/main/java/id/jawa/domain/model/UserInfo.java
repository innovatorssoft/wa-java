package id.jawa.domain.model;

import java.util.List;

public record UserInfo(
    String jid,
    String lid,
    String status,
    String pictureId,
    String verifiedName,
    List<DeviceInfo> devices
) {}
