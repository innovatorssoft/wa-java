package id.jawa.domain.model;

import id.jawa.proto.Wa;

import java.util.Map;

/**
 * A message published in a WhatsApp newsletter channel.
 *
 * @param serverId Monotonic sequence server ID in the newsletter
 * @param messageId Unique message ID string
 * @param type Payload type (e.g. "text", "image", "video")
 * @param timestamp Epoch timestamp seconds of publication
 * @param viewsCount Total recorded views count for this broadcast
 * @param reactionCounts Map of emoji reaction strings to their aggregated counts
 * @param message The parsed Protobuf message payload
 */
public record NewsletterMessage(
    long serverId,
    String messageId,
    String type,
    long timestamp,
    int viewsCount,
    Map<String, Integer> reactionCounts,
    Wa.Message message
) {}
