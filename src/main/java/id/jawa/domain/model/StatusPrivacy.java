package id.jawa.domain.model;

import java.util.List;

public record StatusPrivacy(
    String type,
    boolean isDefault,
    List<String> list
) {}
