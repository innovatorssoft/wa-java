// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.client;

import id.jawa.proto.Wa;
import id.jawa.protocol.codec.BinaryNode;

import java.util.concurrent.CompletableFuture;

/**
 * Service facade handling WhatsApp Newsletter / Channel features in JaWa
 * (sending messages, reactions, and fetching channel message history).
 */
public final class NewsletterService {

    private final JaWaClient client;

    NewsletterService(JaWaClient client) {
        this.client = client;
    }

    /** Send a text message to a WhatsApp Newsletter / Channel. */
    public CompletableFuture<String> sendText(String newsletterJid, String text) {
        return client.sendNewsletterText(newsletterJid, text);
    }

    /** Send a built Protobuf message to a WhatsApp Newsletter / Channel. */
    public CompletableFuture<String> sendMessage(String newsletterJid, Wa.Message msg) {
        return client.sendNewsletterMessage(newsletterJid, msg);
    }

    /** Send an emoji reaction to a message in a WhatsApp Newsletter / Channel. */
    public CompletableFuture<String> sendReaction(String newsletterJid, String serverId, String reactionCode) {
        return client.sendNewsletterReaction(newsletterJid, serverId, reactionCode);
    }

    /** Fetch recent messages from a WhatsApp Newsletter / Channel. */
    public CompletableFuture<BinaryNode> fetchMessages(String newsletterJid, int count) {
        return client.fetchNewsletterMessages(newsletterJid, count);
    }
}
