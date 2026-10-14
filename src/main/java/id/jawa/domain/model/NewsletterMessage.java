package id.jawa.domain.model;

import id.jawa.proto.Wa;

import java.util.Map;

public record NewsletterMessage(
    long serverId,
    String messageId,
    String type,
    long timestamp,
    int viewsCount,
    Map<String, Integer> reactionCounts,
    Wa.Message message
) {}
