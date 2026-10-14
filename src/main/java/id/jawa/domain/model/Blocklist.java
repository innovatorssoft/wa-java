package id.jawa.domain.model;

import java.util.List;

public record Blocklist(
    String dhash,
    String addressingMode,
    List<BlocklistItem> items
) {}
