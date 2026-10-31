/* SPDX-License-Identifier: GPL-3.0-or-later */
package id.ja_wa;

import id.ja_wa.client.JaWaClient;
import id.ja_wa.client.JaWaClient.CarouselCardInput;
import id.ja_wa.client.JaWaClient.Listener;
import id.ja_wa.domain.model.BlocklistItem;
import id.ja_wa.domain.model.ChatPresence;
import id.ja_wa.domain.model.CommunitySubGroup;
import id.ja_wa.domain.model.ContactStatus;
import id.ja_wa.domain.model.GroupJoinRequest;
import id.ja_wa.domain.model.Jid;
import id.ja_wa.domain.model.NewsletterMetadata;
import id.ja_wa.domain.model.PrivacySettings;
import id.ja_wa.domain.store.FileAuthStore;
import id.ja_wa.event.CallEvent;
import id.ja_wa.event.ConnectionUpdate;
import id.ja_wa.event.GroupParticipantsUpdate;
import id.ja_wa.event.MessageUpsert;
import id.ja_wa.event.PresenceUpdate;
import id.ja_wa.event.WaEventListener;
import id.ja_wa.feature.messaging.GroupListQuery;
import id.ja_wa.feature.messaging.GroupMetadataQuery;
import id.ja_wa.feature.messaging.MessageEncoder;
import id.ja_wa.feature.messaging.MessageEncoder.ContactInput;
import id.ja_wa.feature.messaging.MessageEncoder.CtaButton;
import id.ja_wa.feature.messaging.MessageEncoder.GroupMentionInput;
import id.ja_wa.feature.messaging.MessageEncoder.ListRow;
import id.ja_wa.feature.messaging.MessageEncoder.ListSection;
import id.ja_wa.feature.messaging.MessageEncoder.PollVoteInput;
import id.ja_wa.feature.messaging.MessageEncoder.TableRowInput;
import id.ja_wa.feature.messaging.MessageReceiver.Decoded;
import id.ja_wa.feature.messaging.MessageReceiver.InteractiveResponse;
import id.ja_wa.feature.pairing.QrTerminal;
import id.ja_wa.feature.store.InMemoryStore;
import id.ja_wa.proto.Wa;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A comprehensive, highly interactive WhatsApp Bot using the Ja-Wa library.
 * Implements and demonstrates the complete feature set documented in Guide.md:
 *
 * 1. Robust Message Ingestion & Normalization:
 * - autoIgnoreOfflineMessages(false) to prevent dropping valid offline/recent
 * messages.
 * - JID device normalization (bare JID resolution) for bulletproof 1:1 DM
 * routing.
 * - Comprehensive text extraction across conversation, extended text, captions
 * & interactive replies.
 * - Prefix-flexible command dispatching (. / ! # or direct text).
 * - Deduplication cache across Listener and WaEventListener (MessageUpsert).
 *
 * 2. Complete Guide.md Features Showcase:
 * - §1 & §2: Client lifecycle tuning, QR/pairing code, typed WaEventListener
 * events.
 * - §3: InMemoryStore inspection, statistics, and save/load persistence.
 * - §4: Sending messages (text, quoted replies, mentions, mentionAll, group
 * mentions,
 * contacts, location, spoilers, group status, rich code, rich table, polls,
 * quizzes,
 * poll results, payment requests, ephemeral timers, CTA buttons, singleSelect
 * dropdowns,
 * carousels, templates).
 * - §5: Groups & Communities (joined groups, metadata, invite link get/revoke,
 * group creation,
 * subject/description updates, lock/announce settings, join requests, leave,
 * communities creation, sub-groups, invite code, group linking) and Newsletters
 * / Channels
 * (info, follow/unfollow, mute, create, broadcast, subscriptions).
 * - §6: Presence (composing, recording, paused, available/unavailable),
 * receipts (read, played, self),
 * reactions, message edits, message revokes, message starring.
 * - §8: Privacy settings (fetch/update, disappearing default), user blocking /
 * unblocking / blocklist,
 * profile bio/status, profile picture updates/removal, push name update.
 * - §9: Media download from direct or quoted messages.
 * - §10: WhatsApp call links, rejecting calls, status story posts (text &
 * mention), USync isOnWhatsApp,
 * LID-to-PN resolution, contact QR link, and account reachout timelock.
 * - Cross-platform Owner shell command execution (> [cmd] or .exec [cmd]).
 */
public final class SimpleBot {

    public static final String OWNER_NUMBER = System.getProperty("jawa.owner", "62895416602000");

    // Cache of processed message IDs to prevent duplicate handling when both
    // Listener and WaEventListener fire
    private static final Set<String> PROCESSED_MSG_IDS = Collections.synchronizedSet(
            Collections.newSetFromMap(new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 3000;
                }
            }));

    public static void main(String[] args) throws Exception {
        if (System.getProperty("jawa.log") == null) {
            System.setProperty("jawa.log", "WARN");
        }
        try {
            if (org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext ctx) {
                String lvl = System.getProperty("jawa.log", "WARN");
                ch.qos.logback.classic.Level level = ch.qos.logback.classic.Level.toLevel(lvl, ch.qos.logback.classic.Level.WARN);
                ctx.getLogger("id.ja_wa").setLevel(level);
                ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(level);
            }
        } catch (Throwable ignored) {
        }

        Path sessionFile = Path.of(System.getProperty("jawa.session", "sessions/default.session"));

        String suffix = ".session";
        String basePath = sessionFile.toString();
        String derivedSignalDir = basePath.endsWith(suffix)
                ? basePath.substring(0, basePath.length() - suffix.length()) + ".signal"
                : basePath + ".signal";
        Path signalDir = Path.of(System.getProperty("jawa.signal_dir", derivedSignalDir));

        System.out.println("==================================================");
        System.out.println("          Ja-Wa Full-Featured Bot (Guide.md)       ");
        System.out.println("==================================================");
        System.out.println("Session : " + sessionFile.toAbsolutePath());
        System.out.println("Signal  : " + signalDir.toAbsolutePath());
        System.out.println("Owner   : +" + OWNER_NUMBER);
        System.out.println("==================================================");

        FileAuthStore store = new FileAuthStore(sessionFile);
        JaWaClient client = new JaWaClient(store, signalDir)
                .autoReconnect(true)
                .autoIgnoreOfflineMessages(false) // Fix: Prevent dropping messages queued or with clock discrepancy
                .offlineMessageGracePeriodSec(30)
                .autoReadReceipt(false)
                .autoComposingPresence(false)
                .syncVersion(true);

        // 1. Register Legacy Listener (pairing, connection, and direct fallback)
        client.listener(new Listener() {
            @Override
            public void onPaired(String jid, String pushName, String platform) {
                System.out.println(">>> Bot paired successfully: " + jid + " (" + pushName + ") [" + platform + "]");
            }

            @Override
            public void onQr(List<String> qrs) {
                String phone = System.getProperty("jawa.phone");
                if (phone != null && !phone.isBlank()) {
                    client.requestPairingCode(phone, null).whenComplete((code, err) -> {
                        if (err != null) {
                            System.err.println("Pair code error: " + err);
                            return;
                        }
                        System.out.println("\n>>> Pair Code: " + code.substring(0, 4) + "-" + code.substring(4) + "\n");
                    });
                    return;
                }
                if (!qrs.isEmpty()) {
                    System.out.println("\n>>> Scan this QR with Linked Devices:\n");
                    System.out.print(QrTerminal.render(qrs.get(0)));
                }
            }

            @Override
            public void onConnected() {
                System.out.println(">>> Bot connected and listening...");
                client.sendPresence(true);
            }

            @Override
            public void onMessage(Decoded d) {
                processInboundMessage(client, d);
            }

            @Override
            public void onError(Throwable t) {
                System.err.println(">>> Listener error: " + t.getMessage());
            }
        });

        // 2. Register Modern Baileys-compatible Event Listener (Guide.md §2)
        client.addEventListener(new WaEventListener() {
            @Override
            public void onConnectionUpdate(ConnectionUpdate update) {
                if (update.connection() != null) {
                    System.out.println(">>> [EVENT] Connection state: " + update.connection()
                            + (update.isNewLogin() != null ? ", newLogin=" + update.isNewLogin() : ""));
                }
                if (update.lastDisconnectError() != null) {
                    System.out.println(">>> [EVENT] Disconnect detail: " + update.lastDisconnectError());
                }
            }

            @Override
            public void onMessageUpsert(MessageUpsert upsert) {
                if (upsert.messages() != null) {
                    for (Decoded d : upsert.messages()) {
                        processInboundMessage(client, d);
                    }
                }
            }

            @Override
            public void onCall(CallEvent call) {
                System.out.printf(">>> [EVENT] Inbound Call: id=%s from=%s video=%s status=%s%n",
                        call.id(), call.from(), call.isVideo(), call.status());
            }

            @Override
            public void onPresenceUpdate(PresenceUpdate presence) {
                // Background presence notifications logged at trace level
            }

            @Override
            public void onGroupParticipantsUpdate(GroupParticipantsUpdate update) {
                System.out.printf(">>> [EVENT] Group participants update: group=%s action=%s participants=%s%n",
                        update.id(), update.action(), update.participants());
            }
        });

        client.connect();
        client.join();
    }

    // =========================================================================
    // Inbound Message Ingestion & Normalization
    // =========================================================================

    private static void processInboundMessage(JaWaClient client, Decoded d) {
        if (d == null || d.msgId() == null)
            return;

        // Deduplicate across Listener.onMessage and WaEventListener.onMessageUpsert
        if (!PROCESSED_MSG_IDS.add(d.msgId())) {
            return;
        }

        String chatJid = normalizeChatJid(d);
        if (chatJid == null)
            return;

        String rawText = extractText(d);
        System.out.printf(">>> [INBOUND] id=%s from=%s chat=%s text=\"%s\"%n",
                d.msgId(), d.senderJid(), chatJid, rawText != null ? rawText : "(media/action)");

        if (rawText == null || rawText.isBlank()) {
            return;
        }

        rawText = rawText.trim();

        // 1. Owner Shell Execution (> [cmd] or .exec [cmd])
        if (rawText.startsWith("> ") || rawText.startsWith(">") || rawText.toLowerCase().startsWith(".exec ")) {
            String cmdStr = rawText.startsWith("> ") ? rawText.substring(2).trim()
                    : rawText.startsWith(">") ? rawText.substring(1).trim()
                            : rawText.substring(6).trim();
            handleExec(client, chatJid, cmdStr, d);
            return;
        }

        // 2. Command Parsing with Prefix Stripping (. / ! #)
        String cleaned = rawText;
        if (cleaned.startsWith(".") || cleaned.startsWith("!") || cleaned.startsWith("/") || cleaned.startsWith("#")) {
            cleaned = cleaned.substring(1).trim();
        }

        String[] parts = cleaned.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";

        handleCommand(client, chatJid, command, args, rawText, d);
    }

    /**
     * Normalizes the destination conversation JID.
     * Crucial: Strips device suffixes (e.g. 628123:4@s.whatsapp.net ->
     * 628123@s.whatsapp.net)
     * so that USync device resolution and DM routing do not fail!
     */
    private static String normalizeChatJid(Decoded d) {
        String chatJid = d.groupJid() != null ? d.groupJid() : d.senderJid();
        if (chatJid == null)
            return null;
        if (!chatJid.endsWith("@g.us") && !chatJid.endsWith("@newsletter")) {
            Jid parsed = Jid.parse(chatJid);
            if (parsed != null) {
                chatJid = parsed.user() + "@" + parsed.server();
            }
        }
        return chatJid;
    }

    /**
     * Robust text extractor supporting conversation, extended text, captions, and
     * interactive responses.
     */
    private static String extractText(Decoded d) {
        if (d == null)
            return null;
        if (d.text() != null && !d.text().isBlank()) {
            return d.text();
        }
        if (d.interactive() != null) {
            String sel = d.interactive().selectedId();
            if (sel != null && !sel.isBlank())
                return sel;
        }
        Wa.Message msg = d.message();
        if (msg != null) {
            if (msg.hasConversation() && !msg.getConversation().isBlank()) {
                return msg.getConversation();
            }
            if (msg.hasExtendedTextMessage() && msg.getExtendedTextMessage().hasText()) {
                return msg.getExtendedTextMessage().getText();
            }
            if (msg.hasImageMessage() && msg.getImageMessage().hasCaption()) {
                return msg.getImageMessage().getCaption();
            }
            if (msg.hasVideoMessage() && msg.getVideoMessage().hasCaption()) {
                return msg.getVideoMessage().getCaption();
            }
            if (msg.hasDocumentMessage() && msg.getDocumentMessage().hasCaption()) {
                return msg.getDocumentMessage().getCaption();
            }
            if (msg.hasButtonsResponseMessage()) {
                return msg.getButtonsResponseMessage().getSelectedButtonId();
            }
            if (msg.hasListResponseMessage()) {
                return msg.getListResponseMessage().getSingleSelectReply().getSelectedRowId();
            }
            if (msg.hasTemplateButtonReplyMessage()) {
                return msg.getTemplateButtonReplyMessage().getSelectedId();
            }
        }
        return null;
    }

    /**
     * Owner check supporting direct PN, LID resolution, system property, and
     * client's own paired JID/LID.
     */
    private static boolean isOwner(JaWaClient client, String senderJid) {
        if (senderJid == null)
            return false;

        String cleanOwner = OWNER_NUMBER.replaceAll("[^0-9]", "");
        String userPart = senderJid.split("@")[0].split(":")[0];
        String cleanSender = userPart.replaceAll("[^0-9]", "");

        // 1. Direct phone number check
        if (!cleanOwner.isEmpty() && cleanSender.equals(cleanOwner)) {
            return true;
        }

        // 2. Client's own paired credentials check (permits self-testing from the bot's
        // paired phone)
        if (client != null && client.creds() != null) {
            if (client.creds().meJid != null) {
                String ownUser = client.creds().meJid.split("@")[0].split(":")[0].replaceAll("[^0-9]", "");
                if (!ownUser.isEmpty() && cleanSender.equals(ownUser)) {
                    return true;
                }
            }
            if (client.creds().meLid != null) {
                String ownLidUser = client.creds().meLid.split("@")[0].split(":")[0];
                if (userPart.equals(ownLidUser)) {
                    return true;
                }
            }
        }

        // 3. LID to PN resolution via JaWaClient.LID_TO_PN_MAP
        String bareLid = userPart + "@lid";
        String mappedPn = JaWaClient.LID_TO_PN_MAP.get(bareLid);
        if (mappedPn != null) {
            String cleanMappedPn = mappedPn.split("@")[0].split(":")[0].replaceAll("[^0-9]", "");
            if (!cleanOwner.isEmpty() && cleanMappedPn.equals(cleanOwner)) {
                return true;
            }
        }

        // 4. Optional owner LID property check (-Djawa.owner_lid=...)
        String ownerLid = System.getProperty("jawa.owner_lid");
        if (ownerLid != null && !ownerLid.isBlank()) {
            String cleanOwnerLid = ownerLid.split("@")[0].split(":")[0].replaceAll("[^0-9]", "");
            if (cleanSender.equals(cleanOwnerLid)) {
                return true;
            }
        }

        return false;
    }

    // =========================================================================
    // Command Router & Dispatcher
    // =========================================================================

    private static void handleCommand(JaWaClient client, String chatJid, String cmd, String args, String rawText,
            Decoded d) {
        switch (cmd) {
            // --- Navigation & Core ---
            case "menu", "help", "menu_cmd" -> sendMenu(client, chatJid, d);
            case "ping", "ping_cmd" -> sendPing(client, chatJid, d);
            case "status", "info" -> sendBotInfo(client, chatJid, d);
            case "store" -> handleStoreCommand(client, chatJid, args, d);

            // --- Guide.md §4: Messaging, Formats & Interactivity ---
            case "text" -> {
                if (args.isEmpty()) {
                    reply(client, chatJid, "Usage: .text <message>", d);
                    return;
                }
                client.sendText(chatJid, args);
            }
            case "reply" -> {
                if (args.isEmpty()) {
                    reply(client, chatJid, "Usage: .reply <message>", d);
                    return;
                }
                client.messages().sendReply(d, args);
            }
            case "mention" -> handleMention(client, chatJid, args, d);
            case "mentionall" -> handleMentionAll(client, chatJid, args, d);
            case "groupmention" -> handleGroupMention(client, chatJid, args, d);
            case "contact" -> handleSendContact(client, chatJid, args, d);
            case "contacts" -> handleSendContactsArray(client, chatJid, d);
            case "location" -> handleSendLocation(client, chatJid, d);
            case "spoiler" -> handleSpoiler(client, chatJid, args, d);
            case "groupstatus" -> handleGroupStatus(client, chatJid, args, d);
            case "richcode" -> handleRichCode(client, chatJid, args, d);
            case "richtable" -> handleRichTable(client, chatJid, d);
            case "poll" -> handlePoll(client, chatJid, args, d);
            case "quiz" -> handleQuiz(client, chatJid, args, d);
            case "pollresult" -> handlePollResult(client, chatJid, d);
            case "payment" -> handlePayment(client, chatJid, args, d);
            case "ephemeral" -> handleEphemeral(client, chatJid, args, d);
            case "disappearing" -> handleDisappearingDefault(client, chatJid, args, d);

            // --- Guide.md §4.9: Buttons, List & Carousel ---
            case "buttons", "buttons_cmd" -> sendCtaButtons(client, chatJid, d);
            case "list", "list_cmd" -> sendList(client, chatJid, d);
            case "carousel", "carousel_cmd" -> sendCarousel(client, chatJid, d);
            case "template" -> handleInteractiveTemplate(client, chatJid, d);

            // --- Guide.md §4.2, §4.3, §4.6, §9: Media ---
            case "image" -> handleSendImage(client, chatJid, args, d);
            case "video" -> handleSendVideo(client, chatJid, args, d);
            case "audio" -> handleSendAudio(client, chatJid, d);
            case "document" -> handleSendDocument(client, chatJid, args, d);
            case "sticker" -> handleSendSticker(client, chatJid, d);
            case "lottie" -> handleSendLottie(client, chatJid, d);
            case "album" -> handleSendAlbum(client, chatJid, d);
            case "viewonce" -> handleSendViewOnce(client, chatJid, d);
            case "download" -> handleDownloadMedia(client, chatJid, d);

            // --- Guide.md §5: Groups & Communities ---
            case "groups" -> handleListGroups(client, chatJid, d);
            case "groupinfo" -> handleGroupInfo(client, chatJid, d);
            case "grouplink" -> handleGroupLink(client, chatJid, d);
            case "revokelink" -> handleRevokeGroupLink(client, chatJid, d);
            case "creategroup" -> handleCreateGroup(client, chatJid, args, d);
            case "setsubject" -> handleSetGroupSubject(client, chatJid, args, d);
            case "setdesc" -> handleSetGroupDescription(client, chatJid, args, d);
            case "grouplock" -> handleSetGroupLocked(client, chatJid, args, d);
            case "groupannounce" -> handleSetGroupAnnounce(client, chatJid, args, d);
            case "groupapproval" -> handleSetGroupApproval(client, chatJid, args, d);
            case "grouprequests" -> handleGroupRequests(client, chatJid, d);
            case "leave" -> handleLeaveGroup(client, chatJid, d);
            case "createcommunity" -> handleCreateCommunity(client, chatJid, args, d);
            case "subgroups" -> handleSubGroups(client, chatJid, args, d);
            case "communitycode" -> handleCommunityCode(client, chatJid, args, d);
            case "communitylink" -> handleCommunityLink(client, chatJid, args, d);

            // --- Guide.md §5.3: Newsletters / Channels ---
            case "channels" -> handleSubscribedChannels(client, chatJid, d);
            case "channelinfo" -> handleChannelInfo(client, chatJid, args, d);
            case "channelfollow" -> handleChannelFollow(client, chatJid, args, d);
            case "channelunfollow" -> handleChannelUnfollow(client, chatJid, args, d);
            case "channelmute" -> handleChannelMute(client, chatJid, args, d);
            case "createnewsletter" -> handleCreateNewsletter(client, chatJid, args, d);
            case "sendchannel" -> handleSendChannel(client, chatJid, args, d);

            // --- Guide.md §6: Presence, Receipts, Reactions & Edits ---
            case "typing" -> {
                client.sendChatPresence(chatJid, ChatPresence.COMPOSING);
                reply(client, chatJid, "✍️ Chat presence set to COMPOSING", d);
            }
            case "recording" -> {
                client.sendChatPresence(chatJid, ChatPresence.RECORDING);
                reply(client, chatJid, "🎙️ Chat presence set to RECORDING", d);
            }
            case "paused" -> {
                client.sendChatPresence(chatJid, ChatPresence.PAUSED);
                reply(client, chatJid, "⏸️ Chat presence set to PAUSED", d);
            }
            case "available", "online" -> {
                client.sendPresence(true);
                reply(client, chatJid, "🟢 Global presence set to AVAILABLE", d);
            }
            case "unavailable", "offline" -> {
                client.sendPresence(false);
                reply(client, chatJid, "⚪ Global presence set to UNAVAILABLE", d);
            }
            case "react" -> handleReaction(client, chatJid, args, d);
            case "edit" -> handleEdit(client, chatJid, args, d);
            case "revoke" -> handleRevoke(client, chatJid, d);
            case "read" -> {
                client.sendReadReceipt(d);
                reply(client, chatJid, "👁️ Read receipt sent for message id=" + d.msgId(), d);
            }
            case "played" -> {
                client.sendPlayedReceipt(d);
                reply(client, chatJid, "▶️ Played receipt sent for message id=" + d.msgId(), d);
            }
            case "star" -> handleStar(client, chatJid, args, d);

            // --- Guide.md §8: Privacy, Blocking & Profile ---
            case "privacy" -> handleGetPrivacy(client, chatJid, d);
            case "setprivacy" -> handleSetPrivacy(client, chatJid, args, d);
            case "block" -> handleBlock(client, chatJid, args, d);
            case "unblock" -> handleUnblock(client, chatJid, args, d);
            case "blocklist" -> handleGetBlocklist(client, chatJid, d);
            case "getprofile" -> handleGetProfile(client, chatJid, args, d);
            case "setstatus" -> handleSetStatusMessage(client, chatJid, args, d);
            case "setpushname", "patchname" -> handleUpdatePushName(client, chatJid, args, d);
            case "updatepic" -> handleUpdateProfilePic(client, chatJid, d);
            case "removepic" -> handleRemoveProfilePic(client, chatJid, d);

            // --- Guide.md §10: Calls, Stories & Misc ---
            case "calllink" -> handleCreateCallLink(client, chatJid, args, d);
            case "poststatus" -> handlePostStatus(client, chatJid, args, d);
            case "isonwa" -> handleIsOnWhatsApp(client, chatJid, args, d);
            case "resolvelid" -> handleResolveLid(client, chatJid, args, d);
            case "contactqr" -> handleContactQr(client, chatJid, d);
            case "timelock" -> handleReachoutTimelock(client, chatJid, d);

            default -> {
                // Ignore unrecognized non-prefixed text, but respond if it was an explicit
                // command
                if (rawText.startsWith(".") || rawText.startsWith("!") || rawText.startsWith("/")) {
                    reply(client, chatJid, "❓ Unknown command: `." + cmd + "`. Type *.menu* to view all commands.", d);
                }
            }
        }
    }

    // =========================================================================
    // Core Navigation & System
    // =========================================================================

    private static void sendMenu(JaWaClient client, String chatJid, Decoded d) {
        client.sendChatPresence(chatJid, ChatPresence.COMPOSING);

        List<CtaButton> buttons = List.of(
                CtaButton.quickReply("🏓 Ping Bot", "ping_cmd"),
                CtaButton.quickReply("✨ Carousel Slider", "carousel_cmd"),
                CtaButton.singleSelect("📋 Open All Menu Categories", List.of(
                        new ListSection("💬 Messaging & Formats (§4)", List.of(
                                new ListRow("text_demo", ".text <msg>", "Send plain text"),
                                new ListRow("reply_demo", ".reply <msg>", "Send quoted reply"),
                                new ListRow("mention_demo", ".mention <@user>", "Mention specific user"),
                                new ListRow("mentionall_demo", ".mentionall [txt]", "Tag all group members"),
                                new ListRow("spoiler_demo", ".spoiler <text>", "Send spoiler text"),
                                new ListRow("richcode_demo", ".richcode", "Send formatted rich code"),
                                new ListRow("richtable_demo", ".richtable", "Send AI rich table"),
                                new ListRow("poll_demo", ".poll", "Send interactive poll"),
                                new ListRow("quiz_demo", ".quiz", "Send interactive quiz V5"),
                                new ListRow("payment_demo", ".payment <amount>", "Send payment request"))),
                        new ListSection("📸 Media & Attachments (§4.2, §9)", List.of(
                                new ListRow("image_demo", ".image [caption]", "Send JPEG image"),
                                new ListRow("audio_demo", ".audio", "Send voice note audio"),
                                new ListRow("doc_demo", ".document", "Send PDF attachment"),
                                new ListRow("sticker_demo", ".sticker", "Send WebP sticker"),
                                new ListRow("lottie_demo", ".lottie", "Send animated Lottie sticker"),
                                new ListRow("album_demo", ".album", "Send multi-image photo album"),
                                new ListRow("viewonce_demo", ".viewonce", "Send view-once photo"),
                                new ListRow("download_demo", ".download", "Download media file from message"))),
                        new ListSection("👥 Groups & Communities (§5)", List.of(
                                new ListRow("groups_demo", ".groups", "List joined groups"),
                                new ListRow("groupinfo_demo", ".groupinfo", "View current group info & metadata"),
                                new ListRow("grouplink_demo", ".grouplink", "Get group invite link"),
                                new ListRow("revokelink_demo", ".revokelink", "Revoke group invite link"),
                                new ListRow("subgroups_demo", ".subgroups", "View community sub-groups"))),
                        new ListSection("📢 Channels / Newsletters (§5.3)", List.of(
                                new ListRow("channels_demo", ".channels", "List followed channels/newsletters"),
                                new ListRow("channelinfo_demo", ".channelinfo <jid>", "View newsletter details"))),
                        new ListSection("🎭 Presence & Actions (§6)", List.of(
                                new ListRow("typing_demo", ".typing", "Set composing (typing) presence"),
                                new ListRow("recording_demo", ".recording", "Set recording audio presence"),
                                new ListRow("react_demo", ".react <emoji>", "React with emoji to message"),
                                new ListRow("revoke_demo", ".revoke", "Revoke message for everyone (unsend)"))),
                        new ListSection("🔒 Privacy, Profile & Misc (§8, §10)", List.of(
                                new ListRow("privacy_demo", ".privacy", "View account privacy settings"),
                                new ListRow("blocklist_demo", ".blocklist", "View blocked contacts list"),
                                new ListRow("getprofile_demo", ".getprofile", "View profile picture and bio"),
                                new ListRow("calllink_demo", ".calllink", "Create WhatsApp call link"),
                                new ListRow("poststatus_demo", ".poststatus <text>", "Post WhatsApp Story status"),
                                new ListRow("store_demo", ".store", "Check InMemoryStore cache statistics"),
                                new ListRow("buttons_cmd", ".buttons", "Demo CTA Action Buttons"))),
                        new ListSection("💻 Owner Admin", List.of(
                                new ListRow("exec_info", "> [cmd]", "Execute owner shell command"))))));

        var msg = MessageEncoder.interactiveCtaButtons(
                "👋 *Hello! Welcome to Ja-Wa Showcase Bot!*\n\n"
                        + "This bot implements the complete Baileys-port feature set from *Guide.md* (Increments 1–10c).\n\n"
                        + "Please select a menu category or interactive button below:",
                "JaWa Bot (Owner: +" + OWNER_NUMBER + ")",
                buttons);
        sendQuoted(client, chatJid, msg, d);
    }

    private static void sendPing(JaWaClient client, String chatJid, Decoded d) {
        client.sendChatPresence(chatJid, ChatPresence.COMPOSING);

        long totalMemory = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        long freeMemory = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;

        InMemoryStore s = client.store();
        int chatCount = s != null ? s.chatCount() : 0;

        String response = String.format(
                "*🏓 PONG!*\n\n" +
                        "_*Server & Runtime Info:*_\n" +
                        "• *OS*: %s (%s)\n" +
                        "• *CPU Cores*: %d\n" +
                        "• *Java Version*: %s\n" +
                        "• *Active Threads*: %d\n" +
                        "• *Owner*: +%s\n\n" +
                        "_*Memory & Cache:*_\n" +
                        "• *JVM Memory*: %d MB used / %d MB total\n" +
                        "• *Free Memory*: %d MB\n" +
                        "• *Cached Chats*: %d\n" +
                        "• *Signal Sessions*: OK",
                System.getProperty("os.name"), System.getProperty("os.arch"),
                Runtime.getRuntime().availableProcessors(),
                System.getProperty("java.version"),
                Thread.activeCount(),
                OWNER_NUMBER,
                usedMemory, totalMemory, freeMemory,
                chatCount);

        reply(client, chatJid, response, d);
    }

    private static void sendBotInfo(JaWaClient client, String chatJid, Decoded d) {
        String meJid = client.creds() != null ? client.creds().meJid : "unpaired";
        String meLid = client.creds() != null ? client.creds().meLid : "none";
        String pushName = client.creds() != null ? client.creds().pushName : "-";

        String info = String.format(
                "🤖 *JaWa WhatsApp Bot Details*\n\n" +
                        "• *Phone JID*: `%s`\n" +
                        "• *LID*: `%s`\n" +
                        "• *Push Name*: %s\n" +
                        "• *Owner*: +%s\n" +
                        "• *Auto-Reconnect*: %b\n" +
                        "• *Ignore Offline Msgs*: %b\n" +
                        "• *Offline Grace Period*: %ds",
                meJid, meLid, pushName, OWNER_NUMBER,
                client.autoReconnect(),
                client.isAutoIgnoreOfflineMessages(),
                client.offlineMessageGracePeriodSec());
        reply(client, chatJid, info, d);
    }

    private static void handleStoreCommand(JaWaClient client, String chatJid, String args, Decoded d) {
        InMemoryStore store = client.store();
        if (store == null) {
            reply(client, chatJid, "❌ InMemoryStore is not initialized on client.", d);
            return;
        }

        if (args.equalsIgnoreCase("save")) {
            try {
                Path p = Path.of("sessions/store.json");
                client.saveStore(p);
                reply(client, chatJid, "💾 Store saved successfully to `" + p.toAbsolutePath() + "`", d);
            } catch (Exception e) {
                reply(client, chatJid, "❌ Failed to save store: " + e.getMessage(), d);
            }
            return;
        }

        if (args.equalsIgnoreCase("load")) {
            try {
                Path p = Path.of("sessions/store.json");
                boolean loaded = client.loadStore(p);
                reply(client, chatJid, "📂 Store load result: " + loaded + " from `" + p.toAbsolutePath() + "`", d);
            } catch (Exception e) {
                reply(client, chatJid, "❌ Failed to load store: " + e.getMessage(), d);
            }
            return;
        }

        int totalMessages = 0;
        for (var c : store.chats()) {
            totalMessages += store.messages(c.id()).size();
        }

        String stats = String.format(
                "📦 *InMemoryStore Statistics (Guide.md §3)*\n\n" +
                        "• *Cached Chats*: %d\n" +
                        "• *Total Cached Messages*: %d\n" +
                        "• *Current Chat Messages*: %d\n" +
                        "• *Contacts*: %d\n" +
                        "• *Labels*: %d\n" +
                        "• *Tracked Reactions*: %d\n" +
                        "• *LID Mappings*: %d\n\n" +
                        "_Use `.store save` or `.store load` for JSON snapshot persistence._",
                store.chatCount(),
                totalMessages,
                store.messages(chatJid).size(),
                store.contacts().size(),
                store.labels().size(),
                store.reactions().size(),
                store.lidMappings().size());
        reply(client, chatJid, stats, d);
    }

    // =========================================================================
    // Guide.md §4: Sending Messages & Formatting
    // =========================================================================

    private static void handleMention(JaWaClient client, String chatJid, String args, Decoded d) {
        String targetJid = d.senderJid();
        if (!args.isEmpty()) {
            String cleanNum = args.replaceAll("[^0-9]", "");
            if (!cleanNum.isEmpty()) {
                targetJid = cleanNum + "@s.whatsapp.net";
            }
        }
        String bareTarget = targetJid.split("@")[0].split(":")[0] + "@s.whatsapp.net";
        String text = "Hello @ " + bareTarget.split("@")[0] + "! 👋";
        client.sendTextWithMentions(chatJid, text, List.of(bareTarget))
                .whenComplete((id, err) -> logSend("mention", id, err));
    }

    private static void handleMentionAll(JaWaClient client, String chatJid, String args, Decoded d) {
        if (!chatJid.endsWith("@g.us")) {
            reply(client, chatJid, "⚠️ The `.mentionall` command can only be used inside a group.", d);
            return;
        }

        client.groups().queryGroupMetadata(chatJid).whenComplete((meta, err) -> {
            if (err != null || meta == null) {
                reply(client, chatJid, "❌ Failed to fetch group metadata: " + (err != null ? err.getMessage() : "null"),
                        d);
                return;
            }

            List<String> participants = new ArrayList<>();
            for (var p : meta.participants()) {
                participants.add(p.id());
            }

            String customMsg = args.isEmpty() ? "Attention everyone!" : args;
            StringBuilder sb = new StringBuilder("📢 *" + customMsg + "*\n\n");
            for (String p : participants) {
                String user = p.split("@")[0].split(":")[0];
                sb.append("• @").append(user).append("\n");
            }

            client.messages().sendMentionAll(chatJid, sb.toString(), participants)
                    .whenComplete((id, e) -> logSend("mentionAll", id, e));
        });
    }

    private static void handleGroupMention(JaWaClient client, String chatJid, String args, Decoded d) {
        String subject = args.isEmpty() ? "Developer Community" : args;
        GroupMentionInput gm = new GroupMentionInput(chatJid, subject);
        client.messages().sendGroupMentions(chatJid, "Visit group: @" + subject, List.of(gm))
                .whenComplete((id, err) -> logSend("groupMention", id, err));
    }

    private static void handleSendContact(JaWaClient client, String chatJid, String args, Decoded d) {
        String name = "JaWa Bot Support";
        String phone = OWNER_NUMBER;
        if (!args.isEmpty()) {
            String[] split = args.split("\\s+", 2);
            name = split[0];
            if (split.length > 1)
                phone = split[1];
        }

        String vcard = "BEGIN:VCARD\nVERSION:3.0\nFN:" + name + "\nTEL;type=CELL;type=VOICE;waid=" + phone + ":+"
                + phone + "\nEND:VCARD";
        client.sendContact(chatJid, name, vcard)
                .whenComplete((id, err) -> logSend("sendContact", id, err));
    }

    private static void handleSendContactsArray(JaWaClient client, String chatJid, Decoded d) {
        List<ContactInput> contacts = List.of(
                new ContactInput("Admin 1",
                        "BEGIN:VCARD\nVERSION:3.0\nFN:Admin 1\nTEL;waid=" + OWNER_NUMBER + ":+" + OWNER_NUMBER
                                + "\nEND:VCARD"),
                new ContactInput("Support Team",
                        "BEGIN:VCARD\nVERSION:3.0\nFN:Support Team\nTEL;waid=1234567890:+1234567890\nEND:VCARD"));
        client.messages().sendContactsArray(chatJid, "Important Contacts List", contacts)
                .whenComplete((id, err) -> logSend("sendContactsArray", id, err));
    }

    private static void handleSendLocation(JaWaClient client, String chatJid, Decoded d) {
        // Jakarta Monas coordinates
        client.sendLocation(chatJid, -6.175392, 106.827153, "National Monument (Monas)",
                "Gambir, Central Jakarta, Indonesia")
                .whenComplete((id, err) -> logSend("sendLocation", id, err));
    }

    private static void handleSpoiler(JaWaClient client, String chatJid, String args, Decoded d) {
        String secret = args.isEmpty() ? "This is a hidden spoiler text" : args;
        Wa.Message msg = MessageEncoder.spoilerWrap(MessageEncoder.text(secret));
        sendQuoted(client, chatJid, msg, d);
    }

    private static void handleGroupStatus(JaWaClient client, String chatJid, String args, Decoded d) {
        String text = args.isEmpty() ? "Official group update status" : args;
        Wa.Message msg = MessageEncoder.groupStatusWrap(MessageEncoder.text(text));
        sendQuoted(client, chatJid, msg, d);
    }

    private static void handleRichCode(JaWaClient client, String chatJid, String args, Decoded d) {
        String code = "public class HelloJaWa {\n" +
                "    public static void main(String[] args) {\n" +
                "        System.out.println(\"Hello from JaWa WhatsApp Bot!\");\n" +
                "    }\n" +
                "}";
        client.messages().sendRichCode(chatJid, "java", List.of(code))
                .whenComplete((id, err) -> logSend("sendRichCode", id, err));
    }

    private static void handleRichTable(JaWaClient client, String chatJid, Decoded d) {
        List<TableRowInput> rows = List.of(
                new TableRowInput(List.of("Plan", "Price", "Status"), true),
                new TableRowInput(List.of("Basic", "Free", "Available"), false),
                new TableRowInput(List.of("Pro", "$10/mo", "Active"), false),
                new TableRowInput(List.of("Enterprise", "Contact Sales", "Available"), false));
        client.messages().sendRichTable(chatJid, "Service Plans Overview", rows)
                .whenComplete((id, err) -> logSend("sendRichTable", id, err));
    }

    private static void handlePoll(JaWaClient client, String chatJid, String args, Decoded d) {
        String question = "What is your favorite lunch?";
        List<String> options = List.of("Pizza", "Burger", "Sushi", "Pasta");
        if (!args.isEmpty() && args.contains("|")) {
            String[] split = args.split("\\|");
            question = split[0].trim();
            options = new ArrayList<>();
            for (int i = 1; i < split.length; i++) {
                options.add(split[i].trim());
            }
        }
        client.sendPoll(chatJid, question, options, 1)
                .whenComplete((id, err) -> logSend("sendPoll", id, err));
    }

    private static void handleQuiz(JaWaClient client, String chatJid, String args, Decoded d) {
        String question = "What is the capital of France?";
        List<String> options = List.of("Paris", "Lyon", "Marseille", "Nice");
        int correctIndex = 0;

        if (!args.isEmpty() && args.contains("|")) {
            String[] split = args.split("\\|");
            question = split[0].trim();
            options = new ArrayList<>();
            for (int i = 1; i < split.length; i++) {
                options.add(split[i].trim());
            }
        }

        client.messages().sendQuiz(chatJid, question, options, correctIndex)
                .whenComplete((id, err) -> logSend("sendQuiz", id, err));
    }

    private static void handlePollResult(JaWaClient client, String chatJid, Decoded d) {
        List<PollVoteInput> votes = List.of(
                new PollVoteInput("Pizza", 18),
                new PollVoteInput("Burger", 12),
                new PollVoteInput("Sushi", 25));
        client.messages().sendPollResult(chatJid, "Final Lunch Poll Results", votes, false)
                .whenComplete((id, err) -> logSend("sendPollResult", id, err));
    }

    private static void handlePayment(JaWaClient client, String chatJid, String args, Decoded d) {
        long amount1000 = 100_000_000L; // 100.000 in thousandths
        if (!args.isEmpty()) {
            try {
                amount1000 = Long.parseLong(args.replaceAll("[^0-9]", "")) * 1000L;
            } catch (Exception ignored) {
            }
        }

        Wa.Message req = MessageEncoder.requestPaymentMessage("IDR", amount1000, d.senderJid(), "Lunch Payment Share");
        sendQuoted(client, chatJid, req, d);
    }

    private static void handleEphemeral(JaWaClient client, String chatJid, String args, Decoded d) {
        int seconds = 86400; // 24 hours
        if (!args.isEmpty()) {
            try {
                seconds = Integer.parseInt(args.trim());
            } catch (Exception ignored) {
            }
        }
        client.setChatEphemeralTimer(chatJid, seconds);
        reply(client, chatJid, "⏱️ Ephemeral timer for this chat set to " + seconds + " seconds.", d);
    }

    private static void handleDisappearingDefault(JaWaClient client, String chatJid, String args, Decoded d) {
        int seconds = 86400;
        if (!args.isEmpty()) {
            try {
                seconds = Integer.parseInt(args.trim());
            } catch (Exception ignored) {
            }
        }
        final int finalSeconds = seconds;
        client.updateDefaultDisappearingMode(finalSeconds).whenComplete((v, err) -> {
            if (err != null) {
                reply(client, chatJid, "❌ Failed to change default disappearing timer: " + err.getMessage(), d);
            } else {
                reply(client, chatJid, "✅ Account default disappearing timer set to " + finalSeconds + " seconds.", d);
            }
        });
    }

    // =========================================================================
    // Guide.md §4.9: Buttons, List & Carousel
    // =========================================================================

    private static void sendCtaButtons(JaWaClient client, String chatJid, Decoded d) {
        client.sendChatPresence(chatJid, ChatPresence.COMPOSING);
        List<CtaButton> buttons = List.of(
                CtaButton.url("🌐 Open JaWa Website", "https://github.com/jochris/JaWa"),
                CtaButton.copy("📋 Copy Promo Code", "JAWA-BOT-2026"),
                CtaButton.call("📞 Contact Us", "+" + OWNER_NUMBER),
                CtaButton.quickReply("🏓 Ping Bot", "ping_cmd"));
        var msg = MessageEncoder.interactiveCtaButtons(
                "*Select an interactive button below:*", "JaWa Interactive CTA", buttons);
        sendQuoted(client, chatJid, msg, d);
    }

    private static void sendList(JaWaClient client, String chatJid, Decoded d) {
        client.sendChatPresence(chatJid, ChatPresence.COMPOSING);
        List<CtaButton> buttons = List.of(
                CtaButton.singleSelect("Open Dropdown 📋", List.of(
                        new ListSection("Main Actions", List.of(
                                new ListRow("ping_cmd", "Ping", "Check server health and uptime"),
                                new ListRow("menu_cmd", "Show Menu", "Display bot menu navigation"))),
                        new ListSection("Demos", List.of(
                                new ListRow("buttons_cmd", "Interactive Buttons", "Demo CTA buttons"),
                                new ListRow("carousel_cmd", "Carousel Slider", "Demo horizontal scroll card list"))))));
        var msg = MessageEncoder.interactiveCtaButtons(
                "*Select an option from the dropdown:*", "JaWa Dropdown", buttons);
        sendQuoted(client, chatJid, msg, d);
    }

    private static void sendCarousel(JaWaClient client, String chatJid, Decoded d) {
        client.sendChatPresence(chatJid, ChatPresence.COMPOSING);

        byte[] redImageBytes = getDummyImageBytes(0xFF3333, 300, 200, "Red Option");
        byte[] greenImageBytes = getDummyImageBytes(0x33CC33, 300, 200, "Green Option");
        byte[] blueImageBytes = getDummyImageBytes(0x3366FF, 300, 200, "Blue Option");

        List<CarouselCardInput> cards = List.of(
                new CarouselCardInput("Red Option", "Attractive red card description", redImageBytes, "image/jpeg",
                        "Card 1",
                        List.of(CtaButton.quickReply("Choose Red", "ping_cmd"))),
                new CarouselCardInput("Green Option", "Natural green card description", greenImageBytes, "image/jpeg",
                        "Card 2",
                        List.of(CtaButton.quickReply("Choose Green", "ping_cmd"))),
                new CarouselCardInput("Blue Option", "Elegant blue card description", blueImageBytes, "image/jpeg",
                        "Card 3",
                        List.of(CtaButton.quickReply("Choose Blue", "ping_cmd"))));

        Wa.ContextInfo quoted = null;
        if (d != null) {
            String quotedSender = d.groupJid() != null ? d.senderJid() : null;
            var qBuilder = Wa.ContextInfo.newBuilder()
                    .setStanzaId(d.msgId())
                    .setQuotedMessage(Wa.Message.newBuilder()
                            .setConversation(extractText(d) != null ? extractText(d) : "").build());
            if (quotedSender != null && !quotedSender.isBlank()) {
                qBuilder.setParticipant(quotedSender);
            }
            quoted = qBuilder.build();
        }

        client.sendCarousel(chatJid, "*Please swipe the cards below:*", "JaWa Carousel", cards, quoted)
                .whenComplete((id, err) -> logSend("sendCarousel", id, err));
    }

    private static void handleInteractiveTemplate(JaWaClient client, String chatJid, Decoded d) {
        Wa.Message interactive = MessageEncoder.interactiveCtaButtons(
                "Interactive template message", "Footer Info",
                List.of(CtaButton.url("Open Documentation", "https://github.com/jochris/JaWa")));
        Wa.Message templated = MessageEncoder.interactiveAsTemplate(interactive, "tpl-" + System.currentTimeMillis());
        sendQuoted(client, chatJid, templated, d);
    }

    // =========================================================================
    // Guide.md §4.2, §4.3, §4.6, §9: Media Attachments
    // =========================================================================

    private static void handleSendImage(JaWaClient client, String chatJid, String args, Decoded d) {
        String caption = args.isEmpty() ? "JaWa Bot JPEG Image Example" : args;
        byte[] img = getDummyImageBytes(0x128C7E, 400, 300, caption);
        client.media().sendImage(chatJid, img, "image/jpeg", caption)
                .whenComplete((id, err) -> logSend("sendImage", id, err));
    }

    private static void handleSendVideo(JaWaClient client, String chatJid, String args, Decoded d) {
        String caption = args.isEmpty() ? "Sample MP4 Video" : args;
        byte[] dummyVideo = getMinimalMp4Bytes();
        client.media().sendVideo(chatJid, dummyVideo, "video/mp4", caption)
                .whenComplete((id, err) -> logSend("sendVideo", id, err));
    }

    private static void handleSendAudio(JaWaClient client, String chatJid, Decoded d) {
        byte[] audio = getDummyWavAudioBytes();
        client.media().sendAudio(chatJid, audio, "audio/ogg", 3, true)
                .whenComplete((id, err) -> logSend("sendAudio", id, err));
    }

    private static void handleSendDocument(JaWaClient client, String chatJid, String args, Decoded d) {
        String fileName = args.isEmpty() ? "JaWa-Report.pdf" : args;
        byte[] pdf = getDummyPdfBytes("JaWa Document", "Bot report document content.");
        client.media().sendDocument(chatJid, pdf, "application/pdf", fileName, "Official Document")
                .whenComplete((id, err) -> logSend("sendDocument", id, err));
    }

    private static void handleSendSticker(JaWaClient client, String chatJid, Decoded d) {
        client.media().sendSticker(chatJid, MINIMAL_WEBP, false)
                .whenComplete((id, err) -> logSend("sendSticker", id, err));
    }

    private static void handleSendLottie(JaWaClient client, String chatJid, Decoded d) {
        Wa.Message stickerMsg = MessageEncoder.stickerMessage(
                "https://example.com/stk.webp", "/stk.webp", new byte[32],
                new byte[32], new byte[32], MINIMAL_WEBP.length, false);
        Wa.Message lottie = MessageEncoder.wrapAsLottie(stickerMsg);
        sendQuoted(client, chatJid, lottie, d);
    }

    private static void handleSendAlbum(JaWaClient client, String chatJid, Decoded d) {
        reply(client, chatJid, "📸 Sending photo album...", d);
        List<Wa.Message> members = List.of(
                MessageEncoder.imageMessage("https://example.com/1.jpg", "/1.jpg", new byte[32], new byte[32],
                        new byte[32], 1024, "image/jpeg", "Photo 1"),
                MessageEncoder.imageMessage("https://example.com/2.jpg", "/2.jpg", new byte[32], new byte[32],
                        new byte[32], 1024, "image/jpeg", "Photo 2"));
        client.sendAlbum(chatJid, members).whenComplete((ids, err) -> {
            if (err != null) {
                System.err.println("❌ Send album error: " + err.getMessage());
            } else {
                System.out.println("✅ Sent album with ids: " + ids);
            }
        });
    }

    private static void handleSendViewOnce(JaWaClient client, String chatJid, Decoded d) {
        byte[] img = getDummyImageBytes(0xAA0000, 300, 300, "Secret ViewOnce");
        client.sendImageViewOnce(chatJid, img, "image/jpeg", "Secret view-once message")
                .whenComplete((id, err) -> logSend("sendImageViewOnce", id, err));
    }

    private static void handleDownloadMedia(JaWaClient client, String chatJid, Decoded d) {
        reply(client, chatJid, "⏳ Attempting to download media attachment...", d);
        try {
            byte[] bytes = client.downloadMedia(d);
            if (bytes != null && bytes.length > 0) {
                reply(client, chatJid, "✅ Media downloaded successfully! Size: " + bytes.length + " bytes.", d);
            } else {
                reply(client, chatJid,
                        "⚠️ No direct media attachment on this message. Ensure message contains an image/document/audio.",
                        d);
            }
        } catch (Exception e) {
            reply(client, chatJid, "❌ Failed to download media: " + e.getMessage(), d);
        }
    }

    // =========================================================================
    // Guide.md §5: Groups, Communities & Channels
    // =========================================================================

    private static void handleListGroups(JaWaClient client, String chatJid, Decoded d) {
        client.groups().queryJoinedGroups().whenComplete((groups, err) -> {
            if (err != null || groups == null) {
                reply(client, chatJid, "❌ Failed to fetch group list: " + (err != null ? err.getMessage() : "null"), d);
                return;
            }
            StringBuilder sb = new StringBuilder("👥 *Joined Groups (" + groups.size() + "):*\n\n");
            for (GroupListQuery.GroupInfo g : groups) {
                sb.append("• *").append(g.subject() != null ? g.subject() : "Untitled Group").append("*\n")
                        .append("  JID: `").append(g.jid()).append("`\n");
            }
            reply(client, chatJid, sb.toString(), d);
        });
    }

    private static void handleGroupInfo(JaWaClient client, String chatJid, Decoded d) {
        if (!chatJid.endsWith("@g.us")) {
            reply(client, chatJid, "⚠️ The `.groupinfo` command can only be used inside a WhatsApp group.", d);
            return;
        }

        client.groups().queryGroupMetadata(chatJid).whenComplete((meta, err) -> {
            if (err != null || meta == null) {
                reply(client, chatJid, "❌ Failed to fetch group info: " + (err != null ? err.getMessage() : "null"), d);
                return;
            }
            String info = String.format(
                    "📋 *Group Information:*\n\n" +
                            "• *Subject*: %s\n" +
                            "• *JID*: `%s`\n" +
                            "• *Owner*: %s\n" +
                            "• *Member Count*: %d\n" +
                            "• *Only Admins Can Send*: %b\n" +
                            "• *Announcement Mode*: %b\n" +
                            "• *Ephemeral Timer*: %s seconds",
                    meta.subject(), meta.id(), meta.owner(),
                    meta.participants() != null ? meta.participants().size() : 0,
                    meta.restrict(), meta.announce(),
                    meta.ephemeralDuration() != null ? meta.ephemeralDuration().toString() : "0");
            reply(client, chatJid, info, d);
        });
    }

    private static void handleGroupLink(JaWaClient client, String chatJid, Decoded d) {
        if (!chatJid.endsWith("@g.us")) {
            reply(client, chatJid, "⚠️ The `.grouplink` command can only be used inside a group.", d);
            return;
        }
        client.groups().getGroupInviteLink(chatJid).whenComplete((link, err) -> {
            if (err != null || link == null) {
                reply(client, chatJid, "❌ Failed to get invite link (Ensure bot is an Admin): "
                        + (err != null ? err.getMessage() : ""), d);
            } else {
                reply(client, chatJid, "🔗 *Group Invite Link:*\nhttps://chat.whatsapp.com/" + link, d);
            }
        });
    }

    private static void handleRevokeGroupLink(JaWaClient client, String chatJid, Decoded d) {
        if (!chatJid.endsWith("@g.us")) {
            reply(client, chatJid, "⚠️ The `.revokelink` command can only be used inside a group.", d);
            return;
        }
        client.groups().revokeGroupInviteLink(chatJid).whenComplete((res, err) -> {
            if (err != null) {
                reply(client, chatJid, "❌ Failed to revoke link: " + err.getMessage(), d);
            } else {
                reply(client, chatJid, "✅ Group invite link revoked/reset successfully.", d);
            }
        });
    }

    private static void handleCreateGroup(JaWaClient client, String chatJid, String args, Decoded d) {
        String groupName = args.isEmpty() ? "Ja-Wa New Group" : args;
        client.groups().createGroup(groupName, List.of(d.senderJid())).whenComplete((newJid, err) -> {
            if (err != null) {
                reply(client, chatJid, "❌ Failed to create group: " + err.getMessage(), d);
            } else {
                reply(client, chatJid,
                        "✅ New group created successfully!\n• Name: " + groupName + "\n• JID: `" + newJid + "`", d);
            }
        });
    }

    private static void handleSetGroupSubject(JaWaClient client, String chatJid, String args, Decoded d) {
        if (!chatJid.endsWith("@g.us")) {
            reply(client, chatJid, "⚠️ This command can only be used inside a group.", d);
            return;
        }
        if (args.isEmpty()) {
            reply(client, chatJid, "Usage: .setsubject <new subject>", d);
            return;
        }
        client.groups().setGroupSubject(chatJid, args).whenComplete((v, err) -> {
            if (err != null) {
                reply(client, chatJid, "❌ Failed to change group subject: " + err.getMessage(), d);
            } else {
                reply(client, chatJid, "✅ Group subject changed successfully to: *" + args + "*", d);
            }
        });
    }

    private static void handleSetGroupDescription(JaWaClient client, String chatJid, String args, Decoded d) {
        if (!chatJid.endsWith("@g.us")) {
            reply(client, chatJid, "⚠️ This command can only be used inside a group.", d);
            return;
        }
        client.groups().setGroupDescription(chatJid, args).whenComplete((v, err) -> {
            if (err != null) {
                reply(client, chatJid, "❌ Failed to change description: " + err.getMessage(), d);
            } else {
                reply(client, chatJid, "✅ Group description updated successfully.", d);
            }
        });
    }

    private static void handleSetGroupLocked(JaWaClient client, String chatJid, String args, Decoded d) {
        if (!chatJid.endsWith("@g.us")) {
            reply(client, chatJid, "⚠️ This command can only be used inside a group.", d);
            return;
        }
        boolean lock = args.equalsIgnoreCase("true") || args.equalsIgnoreCase("on");
        client.groups().setGroupLocked(chatJid, lock).whenComplete((v, err) -> {
            if (err != null) {
                reply(client, chatJid, "❌ Failed to configure group lock: " + err.getMessage(), d);
            } else {
                reply(client, chatJid, "✅ Group lock status (only admins can edit info): *" + lock + "*", d);
            }
        });
    }

    private static void handleSetGroupAnnounce(JaWaClient client, String chatJid, String args, Decoded d) {
        if (!chatJid.endsWith("@g.us")) {
            reply(client, chatJid, "⚠️ This command can only be used inside a group.", d);
            return;
        }
        boolean announce = args.equalsIgnoreCase("true") || args.equalsIgnoreCase("on");
        client.groups().setGroupAnnounce(chatJid, announce).whenComplete((v, err) -> {
            if (err != null) {
                reply(client, chatJid, "❌ Failed to configure announce mode: " + err.getMessage(), d);
            } else {
                reply(client, chatJid, "✅ Announcement mode (only admins can send messages): *" + announce + "*", d);
            }
        });
    }

    private static void handleSetGroupApproval(JaWaClient client, String chatJid, String args, Decoded d) {
        if (!chatJid.endsWith("@g.us")) {
            reply(client, chatJid, "⚠️ This command can only be used inside a group.", d);
            return;
        }
        boolean approval = args.equalsIgnoreCase("true") || args.equalsIgnoreCase("on");
        client.groups().setGroupJoinApprovalMode(chatJid, approval).whenComplete((v, err) -> {
            if (err != null) {
                reply(client, chatJid, "❌ Failed to change join approval mode: " + err.getMessage(), d);
            } else {
                reply(client, chatJid, "✅ Join approval mode set to: *" + approval + "*", d);
            }
        });
    }

    private static void handleGroupRequests(JaWaClient client, String chatJid, Decoded d) {
        if (!chatJid.endsWith("@g.us")) {
            reply(client, chatJid, "⚠️ This command can only be used inside a group.", d);
            return;
        }
        client.groups().getGroupRequestParticipants(chatJid).whenComplete((requests, err) -> {
            if (err != null || requests == null) {
                reply(client, chatJid, "❌ Failed to fetch member requests: " + (err != null ? err.getMessage() : ""),
                        d);
                return;
            }
            if (requests.isEmpty()) {
                reply(client, chatJid, "ℹ️ No pending group join requests at this time.", d);
                return;
            }
            StringBuilder sb = new StringBuilder("📋 *Group Join Requests (" + requests.size() + "):*\n\n");
            for (GroupJoinRequest r : requests) {
                sb.append("• JID: `").append(r.jid()).append("`\n");
            }
            reply(client, chatJid, sb.toString(), d);
        });
    }

    private static void handleLeaveGroup(JaWaClient client, String chatJid, Decoded d) {
        if (!chatJid.endsWith("@g.us")) {
            reply(client, chatJid, "⚠️ This command can only be used inside a group.", d);
            return;
        }
        reply(client, chatJid, "👋 Goodbye! Bot is leaving this group.", d);
        client.groups().leaveGroup(chatJid).whenComplete((v, err) -> {
            if (err != null)
                System.err.println("Leave group error: " + err.getMessage());
        });
    }

    private static void handleCreateCommunity(JaWaClient client, String chatJid, String args, Decoded d) {
        String name = "Ja-Wa Community";
        String desc = "JaWa Library User Community";
        if (!args.isEmpty() && args.contains("|")) {
            String[] split = args.split("\\|", 2);
            name = split[0].trim();
            desc = split[1].trim();
        }

        final String finalName = name;
        client.groups().createCommunity(name, desc).whenComplete((res, err) -> {
            if (err != null) {
                reply(client, chatJid, "❌ Failed to create community: " + err.getMessage(), d);
            } else {
                reply(client, chatJid, "✅ Community created successfully!\n• Name: " + finalName, d);
            }
        });
    }

    private static void handleSubGroups(JaWaClient client, String chatJid, String args, Decoded d) {
        String targetJid = args.isEmpty() ? chatJid : args.trim();
        client.groups().getSubGroups(targetJid).whenComplete((subgroups, err) -> {
            if (err != null || subgroups == null) {
                reply(client, chatJid,
                        "❌ Failed to fetch community sub-groups: " + (err != null ? err.getMessage() : ""), d);
                return;
            }
            StringBuilder sb = new StringBuilder("🏢 *Community Sub-groups (" + subgroups.size() + "):*\n\n");
            for (CommunitySubGroup sg : subgroups) {
                sb.append("• *").append(sg.subject()).append("* (`").append(sg.jid()).append("`)\n");
            }
            reply(client, chatJid, sb.toString(), d);
        });
    }

    private static void handleCommunityCode(JaWaClient client, String chatJid, String args, Decoded d) {
        String targetJid = args.isEmpty() ? chatJid : args.trim();
        client.groups().getCommunityInviteCode(targetJid).whenComplete((code, err) -> {
            if (err != null) {
                reply(client, chatJid, "❌ Failed to get community code: " + err.getMessage(), d);
            } else {
                reply(client, chatJid, "🔑 *Community Invite Code:* `" + code + "`", d);
            }
        });
    }

    private static void handleCommunityLink(JaWaClient client, String chatJid, String args, Decoded d) {
        String[] split = args.split("\\s+");
        if (split.length < 2) {
            reply(client, chatJid, "Usage: .communitylink <communityJid> <childGroupJid>", d);
            return;
        }
        client.groups().linkGroup(split[0], split[1]).whenComplete((v, err) -> {
            if (err != null) {
                reply(client, chatJid, "❌ Failed to link group to community: " + err.getMessage(), d);
            } else {
                reply(client, chatJid, "✅ Successfully linked group to community.", d);
            }
        });
    }

    private static void handleSubscribedChannels(JaWaClient client, String chatJid, Decoded d) {
        client.newsletters().getSubscribedNewsletters().whenComplete((resp, err) -> {
            if (err != null || resp == null) {
                reply(client, chatJid, "❌ Failed to fetch channel list: " + (err != null ? err.getMessage() : ""), d);
            } else {
                reply(client, chatJid, "📢 *Subscribed Channels / Newsletters:*\n```json\n" + resp + "\n```", d);
            }
        });
    }

    private static void handleChannelInfo(JaWaClient client, String chatJid, String args, Decoded d) {
        if (args.isEmpty()) {
            reply(client, chatJid, "Usage: .channelinfo <newsletterJid>", d);
            return;
        }
        client.newsletters().getInfo(args).whenComplete((info, err) -> {
            if (err != null || info == null) {
                reply(client, chatJid, "❌ Failed to fetch channel info: " + (err != null ? err.getMessage() : ""), d);
                return;
            }
            String detail = String.format(
                    "📢 *Newsletter / Channel Details:*\n\n" +
                            "• *Name*: %s\n" +
                            "• *JID*: `%s`\n" +
                            "• *Subscribers*: %d\n" +
                            "• *Muted*: %b\n" +
                            "• *Role*: %s",
                    info.name(), info.id(),
                    info.subscriberCount(),
                    info.muted(),
                    info.role() != null ? info.role() : "-");
            reply(client, chatJid, detail, d);
        });
    }

    private static void handleChannelFollow(JaWaClient client, String chatJid, String args, Decoded d) {
        if (args.isEmpty()) {
            reply(client, chatJid, "Usage: .channelfollow <newsletterJid>", d);
            return;
        }
        client.newsletters().follow(args).whenComplete((v, err) -> {
            if (err != null)
                reply(client, chatJid, "❌ Failed to follow channel: " + err.getMessage(), d);
            else
                reply(client, chatJid, "✅ Successfully followed channel: `" + args + "`", d);
        });
    }

    private static void handleChannelUnfollow(JaWaClient client, String chatJid, String args, Decoded d) {
        if (args.isEmpty()) {
            reply(client, chatJid, "Usage: .channelunfollow <newsletterJid>", d);
            return;
        }
        client.newsletters().unfollow(args).whenComplete((v, err) -> {
            if (err != null)
                reply(client, chatJid, "❌ Failed to unfollow channel: " + err.getMessage(), d);
            else
                reply(client, chatJid, "✅ Successfully unfollowed channel: `" + args + "`", d);
        });
    }

    private static void handleChannelMute(JaWaClient client, String chatJid, String args, Decoded d) {
        if (args.isEmpty()) {
            reply(client, chatJid, "Usage: .channelmute <newsletterJid>", d);
            return;
        }
        client.newsletters().toggleMute(args, true).whenComplete((v, err) -> {
            if (err != null)
                reply(client, chatJid, "❌ Failed to mute channel: " + err.getMessage(), d);
            else
                reply(client, chatJid, "✅ Channel mute status toggled.", d);
        });
    }

    private static void handleCreateNewsletter(JaWaClient client, String chatJid, String args, Decoded d) {
        String name = "Ja-Wa Channel";
        String desc = "Bot News & Updates Channel";
        if (!args.isEmpty() && args.contains("|")) {
            String[] split = args.split("\\|", 2);
            name = split[0].trim();
            desc = split[1].trim();
        }

        client.newsletters().createNewsletter(name, desc).whenComplete((meta, err) -> {
            if (err != null || meta == null) {
                reply(client, chatJid, "❌ Failed to create newsletter: " + (err != null ? err.getMessage() : ""), d);
            } else {
                reply(client, chatJid,
                        "✅ Channel created successfully!\n• Name: " + meta.name() + "\n• JID: `" + meta.id() + "`", d);
            }
        });
    }

    private static void handleSendChannel(JaWaClient client, String chatJid, String args, Decoded d) {
        String[] split = args.split("\\s+", 2);
        if (split.length < 2) {
            reply(client, chatJid, "Usage: .sendchannel <newsletterJid> <message>", d);
            return;
        }
        client.newsletters().sendText(split[0], split[1]).whenComplete((id, err) -> {
            if (err != null)
                reply(client, chatJid, "❌ Failed to broadcast to channel: " + err.getMessage(), d);
            else
                reply(client, chatJid, "✅ Message successfully sent to channel! ID: " + id, d);
        });
    }

    // =========================================================================
    // Guide.md §6: Presence, Reactions, Edits & Revokes
    // =========================================================================

    private static void handleReaction(JaWaClient client, String chatJid, String args, Decoded d) {
        String emoji = args.isEmpty() ? "❤️" : args.trim();
        client.messages().sendReaction(d, emoji).whenComplete((id, err) -> logSend("sendReaction", id, err));
    }

    private static void handleEdit(JaWaClient client, String chatJid, String args, Decoded d) {
        if (args.isEmpty()) {
            reply(client, chatJid, "Usage: .edit <new text>", d);
            return;
        }
        client.messages().sendEdit(d, args).whenComplete((id, err) -> {
            if (err != null)
                reply(client, chatJid, "❌ Failed to edit message: " + err.getMessage(), d);
            else
                reply(client, chatJid, "✅ Message edited successfully.", d);
        });
    }

    private static void handleRevoke(JaWaClient client, String chatJid, Decoded d) {
        client.messages().sendRevoke(d).whenComplete((id, err) -> {
            if (err != null)
                reply(client, chatJid, "❌ Failed to revoke message: " + err.getMessage(), d);
            else
                reply(client, chatJid, "🗑️ Message revoked/deleted for everyone successfully.", d);
        });
    }

    private static void handleStar(JaWaClient client, String chatJid, String args, Decoded d) {
        boolean star = !args.equalsIgnoreCase("false");
        client.chats().starMessage(chatJid, d.msgId(), star).whenComplete((node, err) -> {
            if (err != null)
                reply(client, chatJid, "❌ Failed to star message: " + err.getMessage(), d);
            else
                reply(client, chatJid, "⭐ Message star status: " + star, d);
        });
    }

    // =========================================================================
    // Guide.md §8: Privacy, Blocking & Profile
    // =========================================================================

    private static void handleGetPrivacy(JaWaClient client, String chatJid, Decoded d) {
        client.fetchPrivacySettings(true).whenComplete((ps, err) -> {
            if (err != null || ps == null) {
                reply(client, chatJid, "❌ Failed to fetch privacy settings: " + (err != null ? err.getMessage() : ""),
                        d);
                return;
            }
            String res = String.format(
                    "🔒 *Account Privacy Settings (Guide.md §8.1):*\n\n" +
                            "• *Read Receipts*: %s\n" +
                            "• *Profile Picture*: %s\n" +
                            "• *Status*: %s\n" +
                            "• *Online*: %s\n" +
                            "• *Last Seen*: %s\n" +
                            "• *Group Add*: %s\n" +
                            "• *Call Add*: %s",
                    ps.readReceipts(), ps.profilePhoto(), ps.status(),
                    ps.online(), ps.lastSeen(), ps.groupAdd(), ps.callAdd());
            reply(client, chatJid, res, d);
        });
    }

    private static void handleSetPrivacy(JaWaClient client, String chatJid, String args, Decoded d) {
        String[] split = args.split("\\s+");
        if (split.length < 2) {
            reply(client, chatJid,
                    "Usage: .setprivacy <category> <all|contacts|none>\nExample: `.setprivacy last none`", d);
            return;
        }
        client.setPrivacySetting(split[0], split[1]).whenComplete((v, err) -> {
            if (err != null)
                reply(client, chatJid, "❌ Failed to change privacy: " + err.getMessage(), d);
            else
                reply(client, chatJid, "✅ Privacy `" + split[0] + "` changed to: *" + split[1] + "*", d);
        });
    }

    private static void handleBlock(JaWaClient client, String chatJid, String args, Decoded d) {
        String target = args.isEmpty() ? d.senderJid() : (args.replaceAll("[^0-9]", "") + "@s.whatsapp.net");
        client.privacy().block(target).whenComplete((res, err) -> {
            if (err != null)
                reply(client, chatJid, "❌ Failed to block: " + err.getMessage(), d);
            else
                reply(client, chatJid, "🚫 Blocked successfully: `" + target + "`", d);
        });
    }

    private static void handleUnblock(JaWaClient client, String chatJid, String args, Decoded d) {
        if (args.isEmpty()) {
            reply(client, chatJid, "Usage: .unblock <number>", d);
            return;
        }
        String target = args.replaceAll("[^0-9]", "") + "@s.whatsapp.net";
        client.privacy().unblock(target).whenComplete((res, err) -> {
            if (err != null)
                reply(client, chatJid, "❌ Failed to unblock: " + err.getMessage(), d);
            else
                reply(client, chatJid, "✅ Unblocked successfully: `" + target + "`", d);
        });
    }

    private static void handleGetBlocklist(JaWaClient client, String chatJid, Decoded d) {
        client.privacy().getBlocklist().whenComplete((bl, err) -> {
            if (err != null || bl == null) {
                reply(client, chatJid, "❌ Failed to fetch blocklist: " + (err != null ? err.getMessage() : ""), d);
                return;
            }
            List<BlocklistItem> items = bl.items();
            if (items == null || items.isEmpty()) {
                reply(client, chatJid, "ℹ️ Blocklist is empty. No contacts are blocked.", d);
                return;
            }
            StringBuilder sb = new StringBuilder("🚫 *Blocked Contacts List (" + items.size() + "):*\n\n");
            for (BlocklistItem item : items) {
                String id = item.pn() != null ? item.pn() : item.lid();
                sb.append("• `").append(id).append("`\n");
            }
            reply(client, chatJid, sb.toString(), d);
        });
    }

    private static void handleGetProfile(JaWaClient client, String chatJid, String args, Decoded d) {
        String target = args.isEmpty() ? d.senderJid() : (args.replaceAll("[^0-9]", "") + "@s.whatsapp.net");

        client.profile().fetchStatus(target).whenComplete((status, err) -> {
            String bio = (err == null && status != null) ? status : "-";
            client.profile().fetchProfilePictureUrl(target).whenComplete((url, err2) -> {
                String pic = (err2 == null && url != null) ? url : "(None or private)";
                String msg = String.format(
                        "👤 *Profile Information (%s)*\n\n" +
                                "• *JID*: `%s`\n" +
                                "• *Bio / About*: %s\n" +
                                "• *Avatar URL*: %s",
                        target, target, bio, pic);
                reply(client, chatJid, msg, d);
            });
        });
    }

    private static void handleSetStatusMessage(JaWaClient client, String chatJid, String args, Decoded d) {
        if (args.isEmpty()) {
            reply(client, chatJid, "Usage: .setstatus <new bio text>", d);
            return;
        }
        client.profile().setStatusMessage(args).whenComplete((id, err) -> {
            if (err != null)
                reply(client, chatJid, "❌ Failed to update status bio: " + err.getMessage(), d);
            else
                reply(client, chatJid, "✅ Status bio updated successfully to: *" + args + "*", d);
        });
    }

    private static void handleUpdatePushName(JaWaClient client, String chatJid, String args, Decoded d) {
        if (args.isEmpty()) {
            reply(client, chatJid, "Usage: .setpushname <new push name>", d);
            return;
        }
        client.profile().updatePushName(args).whenComplete((v, err) -> {
            if (err != null)
                reply(client, chatJid, "❌ Failed to update push name: " + err.getMessage(), d);
            else
                reply(client, chatJid, "✅ Push name updated successfully to: *" + args + "*", d);
        });
    }

    private static void handleUpdateProfilePic(JaWaClient client, String chatJid, Decoded d) {
        byte[] pic = getDummyImageBytes(0x0066CC, 300, 300, "Ja-Wa Avatar");
        client.profile().updateProfilePicture(pic).whenComplete((node, err) -> {
            if (err != null)
                reply(client, chatJid, "❌ Failed to update profile picture: " + err.getMessage(), d);
            else
                reply(client, chatJid, "✅ Bot profile picture updated successfully.", d);
        });
    }

    private static void handleRemoveProfilePic(JaWaClient client, String chatJid, Decoded d) {
        client.profile().removeProfilePicture().whenComplete((node, err) -> {
            if (err != null)
                reply(client, chatJid, "❌ Failed to remove profile picture: " + err.getMessage(), d);
            else
                reply(client, chatJid, "✅ Bot profile picture removed successfully.", d);
        });
    }

    // =========================================================================
    // Guide.md §10: Calls, Stories & Misc
    // =========================================================================

    private static void handleCreateCallLink(JaWaClient client, String chatJid, String args, Decoded d) {
        String type = args.equalsIgnoreCase("audio") ? "audio" : "video";
        client.createCallLink(type, null).whenComplete((link, err) -> {
            if (err != null || link == null) {
                reply(client, chatJid, "❌ Failed to create call link: " + (err != null ? err.getMessage() : ""), d);
            } else {
                reply(client, chatJid, "📞 *WhatsApp Call Link (" + type + "):*\n" + link, d);
            }
        });
    }

    private static void handlePostStatus(JaWaClient client, String chatJid, String args, Decoded d) {
        String text = args.isEmpty() ? "Status update from Ja-Wa Bot! 🚀" : args;
        client.sendStatusText(text, 0xFF128C7E, 1).whenComplete((id, err) -> {
            if (err != null)
                reply(client, chatJid, "❌ Failed to post status story: " + err.getMessage(), d);
            else
                reply(client, chatJid, "✅ WhatsApp Story status posted successfully! ID: " + id, d);
        });
    }

    private static void handleIsOnWhatsApp(JaWaClient client, String chatJid, String args, Decoded d) {
        if (args.isEmpty()) {
            reply(client, chatJid, "Usage: .isonwa <number>\nExample: `.isonwa 1234567890`", d);
            return;
        }
        String phone = args.replaceAll("[^0-9]", "");
        client.isOnWhatsApp(List.of(phone)).whenComplete((map, err) -> {
            if (err != null || map == null) {
                reply(client, chatJid, "❌ Failed to check number: " + (err != null ? err.getMessage() : ""), d);
                return;
            }
            ContactStatus status = map.get(phone);
            if (status != null && status.isOnWhatsApp()) {
                reply(client, chatJid,
                        "✅ Number +" + phone + " *registered* on WhatsApp!\n• JID: `" + status.jid() + "`", d);
            } else {
                reply(client, chatJid, "❌ Number +" + phone + " *not registered* on WhatsApp.", d);
            }
        });
    }

    private static void handleResolveLid(JaWaClient client, String chatJid, String args, Decoded d) {
        String lid = args.isEmpty() ? (d.senderJid().endsWith("@lid") ? d.senderJid() : "") : args.trim();
        if (lid.isEmpty() || !lid.endsWith("@lid")) {
            reply(client, chatJid, "Usage: .resolvelid <user@lid>", d);
            return;
        }
        client.resolveLidToPn(lid).whenComplete((pn, err) -> {
            if (err != null || pn == null) {
                reply(client, chatJid,
                        "❌ Unable to resolve LID `" + lid + "`: " + (err != null ? err.getMessage() : "not found"), d);
            } else {
                reply(client, chatJid,
                        "🔍 *LID to Phone Number Resolution Result:*\n• LID: `" + lid + "`\n• PN: `" + pn + "`", d);
            }
        });
    }

    private static void handleContactQr(JaWaClient client, String chatJid, Decoded d) {
        client.getContactQrLink(false).whenComplete((link, err) -> {
            if (err != null || link == null) {
                reply(client, chatJid, "❌ Failed to get Contact QR link: " + (err != null ? err.getMessage() : ""), d);
            } else {
                reply(client, chatJid, "📲 *Account Contact QR Link:*\n" + link, d);
            }
        });
    }

    private static void handleReachoutTimelock(JaWaClient client, String chatJid, Decoded d) {
        client.messages().fetchAccountReachoutTimelock().whenComplete((lock, err) -> {
            if (err != null || lock == null) {
                reply(client, chatJid, "❌ Failed to fetch reachout timelock: " + (err != null ? err.getMessage() : ""),
                        d);
            } else {
                reply(client, chatJid, "⏱️ *Account Reachout Timelock:*\n• Active: " + lock.isActive()
                        + "\n• Ends: " + lock.timeEnforcementEndsEpochMs() + " ms"
                        + "\n• Type: " + lock.enforcementType(), d);
            }
        });
    }

    // =========================================================================
    // Owner Shell Execution (Cross-Platform)
    // =========================================================================

    private static void handleExec(JaWaClient client, String chatJid, String commandStr, Decoded d) {
        String senderJid = d != null ? d.senderJid() : null;
        if (!isOwner(client, senderJid)) {
            System.out.println(">>> Exec denied for sender: " + senderJid + " (Owner required: " + OWNER_NUMBER + ")");
            reply(client, chatJid, "⚠️ *Access Denied!* Exec command can only be executed by the bot Owner (+"
                    + OWNER_NUMBER + ").\n\nSender JID: `" + senderJid + "`", d);
            return;
        }

        System.out.println(">>> Exec granted for owner " + senderJid + ": " + commandStr);
        client.sendChatPresence(chatJid, ChatPresence.COMPOSING);
        try {
            boolean isWin = System.getProperty("os.name", "").toLowerCase().contains("win");
            ProcessBuilder pb = isWin
                    ? new ProcessBuilder("cmd.exe", "/c", commandStr)
                    : new ProcessBuilder("bash", "-c", commandStr);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                output.append("\n[Execution timed out after 15s]");
            }

            String resultText = output.toString().trim();
            if (resultText.isEmpty()) {
                resultText = "(Command executed successfully with no output)";
            }

            reply(client, chatJid, "💻 *Exec Result:*\n```\n" + resultText + "\n```", d);
        } catch (Exception e) {
            reply(client, chatJid, "❌ *Exec Error:*\n```\n" + e.getMessage() + "\n```", d);
        }
    }

    // =========================================================================
    // Helper Methods & Dummy Media Generators
    // =========================================================================

    private static CompletableFuture<String> reply(JaWaClient client, String chatJid, String text, Decoded d) {
        var msg = MessageEncoder.text(text);
        if (d != null) {
            String quotedSender = d.groupJid() != null ? d.senderJid() : null;
            String quotedText = extractText(d);
            msg = MessageEncoder.quote(msg, d.msgId(), quotedSender, quotedText != null ? quotedText : "");
        }
        return client.sendMessage(chatJid, msg).whenComplete((id, err) -> {
            if (err != null) {
                System.err.println("❌ Failed to send reply to " + chatJid + ": " + err.getMessage());
            } else {
                System.out.println("✅ Sent reply id=" + id + " to " + chatJid);
            }
        });
    }

    private static void sendQuoted(JaWaClient client, String chatJid, Wa.Message msg, Decoded d) {
        if (d != null) {
            String quotedSender = d.groupJid() != null ? d.senderJid() : null;
            String quotedText = extractText(d);
            msg = MessageEncoder.quote(msg, d.msgId(), quotedSender, quotedText != null ? quotedText : "");
        }
        client.sendMessage(chatJid, msg).whenComplete((id, err) -> {
            if (err != null) {
                System.err.println("❌ Failed to send message to " + chatJid + ": " + err.getMessage());
            } else {
                System.out.println("✅ Sent message id=" + id + " to " + chatJid);
            }
        });
    }

    private static void logSend(String action, String id, Throwable err) {
        if (err != null) {
            System.err.println("❌ [" + action + "] Error: " + err.getMessage());
        } else {
            System.out.println("✅ [" + action + "] Success, messageId=" + id);
        }
    }

    private static byte[] getDummyImageBytes(int rgbColor, int width, int height, String label) {
        try {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(rgbColor));
            g.fillRect(0, 0, width, height);
            if (label != null && !label.isBlank()) {
                g.setColor(Color.WHITE);
                g.setFont(new Font("SansSerif", Font.BOLD, Math.max(12, height / 10)));
                g.drawString(label, 20, height / 2);
            }
            g.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "jpeg", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static final byte[] MINIMAL_WEBP = new byte[] {
            0x52, 0x49, 0x46, 0x46, 0x1A, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50,
            0x56, 0x50, 0x38, 0x4C, 0x0E, 0x00, 0x00, 0x00, 0x2F, 0x00, 0x00, 0x00,
            0x10, 0x07, 0x10, 0x11, 0x11, (byte) 0x88, 0x08, 0x00
    };

    private static byte[] getDummyPdfBytes(String title, String content) {
        String pdf = "%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
                + "2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
                + "3 0 obj<</Type/Page/MediaBox[0 0 300 144]/Parent 2 0 R/Resources<<>>>>endobj\n"
                + "xref\n0 4\n0000000000 65535 f \n0000000009 00000 n \n0000000052 00000 n \n0000000101 00000 n \n"
                + "trailer<</Size 4/Root 1 0 R>>\nstartxref\n178\n%%EOF\n";
        return pdf.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] getDummyWavAudioBytes() {
        int sampleRate = 8000;
        int numSamples = sampleRate; // 1 second
        int dataSize = numSamples * 2;
        byte[] bytes = new byte[44 + dataSize];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(36 + dataSize);
        bb.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(16); // subchunk1 size
        bb.putShort((short) 1); // PCM format
        bb.putShort((short) 1); // 1 channel
        bb.putInt(sampleRate);
        bb.putInt(sampleRate * 2);
        bb.putShort((short) 2); // block align
        bb.putShort((short) 16); // bits per sample
        bb.put("data".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(dataSize);
        return bytes;
    }

    private static byte[] getMinimalMp4Bytes() {
        // Minimal valid MP4 ftyp container header
        return new byte[] {
                0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70,
                0x69, 0x73, 0x6F, 0x6D, 0x00, 0x00, 0x02, 0x00,
                0x69, 0x73, 0x6F, 0x6D, 0x69, 0x73, 0x6F, 0x32,
                0x61, 0x76, 0x63, 0x31, 0x6D, 0x70, 0x34, 0x31
        };
    }
}
