package id.ja_wa.event;

import id.ja_wa.feature.newsletter.NewsletterLinkedProfiles;
import id.ja_wa.protocol.codec.BinaryNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses Baileys {@code mex} notifications into Baileys events.
 *
 * <p>Source: {@code handleMexNotification} and
 * {@code handleLegacyMexNewsletterNotification} in Baileys
 * {@code lib/Socket/messages-recv.js}, dispatched from the
 * {@code notification type="mex"} case. A mex notification carries a
 * child {@code <update op_name="...">} whose content is JSON text; in
 * wa-java's node model that content is reachable as a {@link String} or
 * as UTF-8 {@code byte[]} through {@link BinaryNode#textContent()}, so
 * no node-model gap blocks this parser.
 *
 * <p>Dispatch rules, mirroring Baileys exactly:
 * <ul>
 *   <li>No {@code <update>} child, or an {@code <update>} without
 *       {@code op_name}: fall back to the legacy path.</li>
 *   <li>Malformed JSON, a non-empty GraphQL {@code errors} array, or a
 *       null {@code data} object: ignored, never thrown (Baileys logs
 *       and returns).</li>
 *   <li>{@code MessageCappingInfoNotification}: emits
 *       {@code message-capping.update} with
 *       {@code data.xwa2_notify_new_chat_messages_capping_info_update}
 *       carried verbatim as raw JSON — no field list exists in Baileys,
 *       so {@link MessageCappingUpdate} carries only that raw JSON.</li>
 *   <li>{@code NotificationUserReachoutTimelockUpdate}: emits a partial
 *       {@code connection.update} carrying only
 *       {@code reachoutTimeLock} (WA Web defaults to now+60s when the
 *       server omits the expiry; an unknown enforcement type falls back
 *       to {@code DEFAULT}).</li>
 *   <li>{@code NotificationLinkedProfilesUpdates}: emits one
 *       {@code lid-mapping.update} per (lid, pn) pair, reusing the
 *       Increment 3 {@link NewsletterLinkedProfiles} parser for the
 *       {@code data.data.xwa2_notify_linked_profiles} shape.</li>
 *   <li>The newsletter ops listed in {@link #LEGACY_NEWSLETTER_OPS}:
 *       routed to the legacy path below (Baileys: "newsletter ops still
 *       use the legacy {@code <mex>} child structure").</li>
 *   <li>Any other {@code op_name}: ignored (Baileys logs "unhandled mex
 *       notification").</li>
 * </ul>
 *
 * <p>Legacy path ({@code handleLegacyMexNewsletterNotification}):
 * the payload node is the {@code <mex>} child when it carries content,
 * otherwise the {@code <update>} child or the notification's first
 * child; a list payload is invalid. The operation comes from the JSON
 * {@code operation} field or the payload node's {@code op_name}
 * attribute, and {@code updates} from the JSON {@code updates} array
 * (falling back to {@code data.data.xwa2_notify_linked_profiles} for
 * linked-profiles payloads). Only three operations produce events here
 * — the same three Baileys' legacy switch handles:
 * {@code NotificationNewsletterUpdate} (settings with at least one key
 * become {@code newsletter-settings.update}; the verbatim mex settings
 * object is not modelled, only {@code name}/{@code description} are
 * carried, per {@link NewsletterSettingsUpdate}),
 * {@code NotificationNewsletterAdminPromote} (becomes
 * {@code newsletter-participants.update} with {@code action=promote},
 * {@code new_role=ADMIN}, {@code author} = the notification's
 * {@code from} attribute), and
 * {@code NotificationLinkedProfilesUpdates} (one
 * {@code lid-mapping.update} per (lid, pn) pair). Every other legacy
 * operation — including {@code NotificationNewsletterAdminDemote} —
 * is unhandled in Baileys' legacy switch and produces no event here.
 *
 * <p>wa-java has no JSON library, so parsing is string-scanning scoped
 * to these payload shapes (the same approach as the Increment 3 mex
 * parsers). Anything that does not match a shape yields no events
 * rather than a guessed event. This method never throws.
 */
public final class MexNotificationParser {

    /** Newsletter ops Baileys routes from the modern path to the legacy path. */
    public static final Set<String> LEGACY_NEWSLETTER_OPS = Set.of(
        "NotificationNewsletterUpdate",
        "NotificationLinkedProfilesUpdates",
        "NotificationNewsletterAdminPromote",
        "NotificationNewsletterAdminDemote",
        "NotificationNewsletterUserSettingChange",
        "NotificationNewsletterJoin",
        "NotificationNewsletterLeave",
        "NotificationNewsletterStateChange",
        "NotificationNewsletterAdminMetadataUpdate",
        "NotificationNewsletterOwnerUpdate",
        "NotificationNewsletterAdminInviteRevoke",
        "NotificationNewsletterWamoSubStatusChange",
        "NotificationNewsletterBlockUser",
        "NotificationNewsletterPaidPartnership",
        "NotificationNewsletterMilestone",
        "NewsletterResponseStateUpdate"
    );

    private static final Set<String> REACHOUT_ENFORCEMENT_TYPES = Set.of(
        "BIZ_COMMERCE_VIOLATION_ALCOHOL",
        "BIZ_COMMERCE_VIOLATION_ADULT",
        "BIZ_COMMERCE_VIOLATION_ANIMALS",
        "BIZ_COMMERCE_VIOLATION_BODY_PARTS_FLUIDS",
        "BIZ_COMMERCE_VIOLATION_DATING",
        "BIZ_COMMERCE_VIOLATION_DIGITAL_SERVICES_PRODUCTS",
        "BIZ_COMMERCE_VIOLATION_DRUGS",
        "BIZ_COMMERCE_VIOLATION_DRUGS_ONLY_OTC",
        "BIZ_COMMERCE_VIOLATION_GAMBLING",
        "BIZ_COMMERCE_VIOLATION_HEALTHCARE",
        "BIZ_COMMERCE_VIOLATION_REAL_FAKE_CURRENCY",
        "BIZ_COMMERCE_VIOLATION_SUPPLEMENTS",
        "BIZ_COMMERCE_VIOLATION_TOBACCO",
        "BIZ_COMMERCE_VIOLATION_VIOLENT_CONTENT",
        "BIZ_COMMERCE_VIOLATION_WEAPONS",
        "BIZ_QUALITY",
        "DEFAULT",
        "WEB_COMPANION_ONLY"
    );

    private MexNotificationParser() {}

    /**
     * Parses a {@code <notification type="mex">} stanza.
     *
     * @param node notification node; null yields an empty list
     * @return events to dispatch, in Baileys' emission order; never null
     */
    public static List<EventDispatch> parse(BinaryNode node) {
        try {
            if (node == null) return List.of();
            BinaryNode updateNode = node.child("update");
            if (updateNode == null) {
                return parseLegacy(node);
            }
            String opName = updateNode.attr("op_name");
            if (opName == null || opName.isBlank()) {
                return parseLegacy(node);
            }
            String json = textContent(updateNode);
            if (json == null || json.isBlank()) {
                return List.of();
            }
            if (hasNonEmptyErrors(json) || hasNullData(json)) {
                return List.of();
            }
            switch (opName) {
                case "MessageCappingInfoNotification" -> {
                    String raw = extractObject(json,
                        "xwa2_notify_new_chat_messages_capping_info_update");
                    if (raw == null) return List.of();
                    return List.of(new EventDispatch(
                        BaileysEventType.MESSAGE_CAPPING_UPDATE,
                        new MessageCappingUpdate(raw)));
                }
                case "NotificationUserReachoutTimelockUpdate" -> {
                    ConnectionUpdate update = parseReachout(json);
                    if (update == null) return List.of();
                    return List.of(new EventDispatch(
                        BaileysEventType.CONNECTION_UPDATE, update));
                }
                case "NotificationLinkedProfilesUpdates" -> {
                    NewsletterLinkedProfiles.LinkedProfilesUpdate parsed =
                        NewsletterLinkedProfiles.parse(json);
                    if (parsed == null) return List.of();
                    return lidMappings(parsed.lidJid(), parsed.addedPnJids());
                }
                default -> {
                    if (LEGACY_NEWSLETTER_OPS.contains(opName)) {
                        return parseLegacy(node);
                    }
                    return List.of();
                }
            }
        } catch (Throwable ignored) {
            // Baileys logs and returns on mex failures; never break the reader loop.
            return List.of();
        }
    }

    /**
     * Legacy mex path: parses the {@code <mex>} / {@code <update>} /
     * first-child payload as {@code {operation, updates}}.
     *
     * @param node notification node; null yields an empty list
     * @return events to dispatch; never null
     */
    public static List<EventDispatch> parseLegacy(BinaryNode node) {
        try {
            if (node == null) return List.of();
            BinaryNode mexNode = node.child("mex");
            BinaryNode payloadNode;
            if (mexNode != null && mexNode.content() != null) {
                payloadNode = mexNode;
            } else {
                BinaryNode updateNode = node.child("update");
                if (updateNode != null) {
                    payloadNode = updateNode;
                } else {
                    List<BinaryNode> children = node.childrenList();
                    if (children.isEmpty()) return List.of();
                    payloadNode = children.get(0);
                }
            }
            if (payloadNode.content() instanceof List) {
                return List.of();
            }
            String json = textContent(payloadNode);
            if (json == null || json.isBlank()) return List.of();
            String operation = extractString(json, "operation");
            if (operation == null || operation.isBlank()) {
                operation = payloadNode.attr("op_name");
            }
            if (operation == null || operation.isBlank()) return List.of();
            List<String> updates = extractArrayObjects(json, "updates");
            if (updates == null) {
                // Baileys' linked-profiles fallback: a single-element
                // updates list synthesized from data.data.xwa2_notify_linked_profiles.
                if ("NotificationLinkedProfilesUpdates".equals(operation)) {
                    NewsletterLinkedProfiles.LinkedProfilesUpdate parsed =
                        NewsletterLinkedProfiles.parse(json);
                    if (parsed == null) return List.of();
                    return lidMappings(parsed.lidJid(), parsed.addedPnJids());
                }
                return List.of();
            }
            String from = node.attr("from");
            switch (operation) {
                case "NotificationNewsletterUpdate" -> {
                    List<EventDispatch> out = new ArrayList<>();
                    for (String update : updates) {
                        String jid = extractString(update, "jid");
                        String settings = extractObject(update, "settings");
                        if (jid == null || settings == null) continue;
                        if (settings.replaceAll("\\s", "").length() <= 2) continue;
                        out.add(new EventDispatch(
                            BaileysEventType.NEWSLETTER_SETTINGS_UPDATE,
                            new NewsletterSettingsUpdate(jid,
                                extractString(settings, "name"),
                                extractString(settings, "description"))));
                    }
                    return out;
                }
                case "NotificationNewsletterAdminPromote" -> {
                    List<EventDispatch> out = new ArrayList<>();
                    for (String update : updates) {
                        String jid = extractString(update, "jid");
                        String user = extractString(update, "user");
                        if (jid == null || user == null) continue;
                        out.add(new EventDispatch(
                            BaileysEventType.NEWSLETTER_PARTICIPANTS_UPDATE,
                            new NewsletterParticipantsUpdate(jid, from, user,
                                "promote", "ADMIN")));
                    }
                    return out;
                }
                case "NotificationLinkedProfilesUpdates" -> {
                    List<EventDispatch> out = new ArrayList<>();
                    for (String update : updates) {
                        String lid = extractString(update, "jid");
                        if (lid == null) continue;
                        out.addAll(lidMappings(lid, parseAddedProfiles(update)));
                    }
                    return out;
                }
                default -> {
                    return List.of();
                }
            }
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static List<EventDispatch> lidMappings(String lid, List<String> pns) {
        List<EventDispatch> out = new ArrayList<>();
        if (lid == null || pns == null) return out;
        for (String pn : pns) {
            if (pn != null && !pn.isBlank()) {
                out.add(new EventDispatch(BaileysEventType.LID_MAPPING_UPDATE,
                    new LidMappingUpdate(lid, pn)));
            }
        }
        return out;
    }

    private static ConnectionUpdate parseReachout(String json) {
        String payload = extractObject(json, "xwa2_notify_account_reachout_timelock");
        if (payload == null) return null;
        Boolean active = extractBoolean(payload, "is_active");
        if (active == null) return null;
        if (!active) {
            return new ConnectionUpdate(null, null, null, null, null, null, null,
                new ConnectionUpdate.ReachoutTimeLock(false, null, "DEFAULT"));
        }
        Long ends = null;
        String endsRaw = extractString(payload, "time_enforcement_ends");
        if (endsRaw != null) {
            try {
                ends = Long.parseLong(endsRaw.trim()) * 1000L;
            } catch (NumberFormatException ignored) {
                ends = null;
            }
        }
        if (ends == null) {
            ends = System.currentTimeMillis() + 60_000L;
        }
        String enforcement = extractString(payload, "enforcement_type");
        if (enforcement == null || !REACHOUT_ENFORCEMENT_TYPES.contains(enforcement)) {
            enforcement = "DEFAULT";
        }
        return new ConnectionUpdate(null, null, null, null, null, null, null,
            new ConnectionUpdate.ReachoutTimeLock(true, ends, enforcement));
    }

    /** Added profiles: bare JID strings, or objects carrying {@code pn} / {@code jid}. */
    private static List<String> parseAddedProfiles(String updateObj) {
        List<String> out = new ArrayList<>();
        int idx = updateObj.indexOf("\"added_profiles\"");
        if (idx < 0) return out;
        int colon = updateObj.indexOf(':', idx);
        if (colon < 0) return out;
        int bracket = updateObj.indexOf('[', colon);
        if (bracket < 0) return out;
        int end = findMatching(updateObj, bracket, '[', ']');
        if (end < 0) return out;
        String array = updateObj.substring(bracket, end + 1);
        int i = 0;
        while (i < array.length()) {
            if (array.charAt(i) == '"') {
                String[] lit = readStringLiteral(array, i);
                if (lit == null) break;
                i = Integer.parseInt(lit[1]);
                String value = lit[0];
                if (!"pn".equals(value) && !"jid".equals(value) && value.contains("@")) {
                    out.add(value);
                }
            } else {
                i++;
            }
        }
        return out;
    }

    // ---- minimal JSON scanning helpers (no JSON library in wa-java) ----

    private static String textContent(BinaryNode node) {
        try {
            return node.textContent();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean hasNonEmptyErrors(String json) {
        int idx = json.indexOf("\"errors\"");
        if (idx < 0) return false;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return false;
        int i = skipWs(json, colon + 1);
        if (i >= json.length() || json.charAt(i) != '[') return false;
        int j = skipWs(json, i + 1);
        return j < json.length() && json.charAt(j) != ']';
    }

    private static boolean hasNullData(String json) {
        int idx = json.indexOf("\"data\"");
        if (idx < 0) return true;
        int colon = json.indexOf(':', idx);
        if (colon < 0) return true;
        int i = skipWs(json, colon + 1);
        return i + 4 <= json.length() && json.startsWith("null", i);
    }

    /** First string value for {@code "field": "..."} anywhere in the JSON text, or null. */
    static String extractString(String json, String field) {
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int i = skipWs(json, colon + 1);
        if (i >= json.length() || json.charAt(i) != '"') return null;
        String[] lit = readStringLiteral(json, i);
        return lit == null ? null : lit[0];
    }

    private static Boolean extractBoolean(String json, String field) {
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int i = skipWs(json, colon + 1);
        if (json.startsWith("true", i)) return Boolean.TRUE;
        if (json.startsWith("false", i)) return Boolean.FALSE;
        return null;
    }

    /** The balanced {@code {...}} object for {@code "field": {...}}, or null. */
    static String extractObject(String json, String field) {
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int brace = json.indexOf('{', colon);
        if (brace < 0) return null;
        int end = findMatching(json, brace, '{', '}');
        if (end < 0) return null;
        return json.substring(brace, end + 1);
    }

    /** Top-level objects of the {@code "field": [...]} array, or null when the field is absent. */
    static List<String> extractArrayObjects(String json, String field) {
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int bracket = json.indexOf('[', colon);
        if (bracket < 0) return null;
        int end = findMatching(json, bracket, '[', ']');
        if (end < 0) return null;
        List<String> out = new ArrayList<>();
        int i = bracket + 1;
        while (i < end) {
            char c = json.charAt(i);
            if (c == '"') {
                String[] lit = readStringLiteral(json, i);
                if (lit == null) break;
                i = Integer.parseInt(lit[1]);
            } else if (c == '{') {
                int objEnd = findMatching(json, i, '{', '}');
                if (objEnd < 0 || objEnd > end) break;
                out.add(json.substring(i, objEnd + 1));
                i = objEnd + 1;
            } else {
                i++;
            }
        }
        return out;
    }

    /** Index just past the matching closer, honouring string literals; -1 when unbalanced. */
    private static int findMatching(String s, int open, char openCh, char closeCh) {
        int depth = 0;
        int i = open;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                String[] lit = readStringLiteral(s, i);
                if (lit == null) return -1;
                i = Integer.parseInt(lit[1]);
            } else if (c == openCh) {
                depth++;
                i++;
            } else if (c == closeCh) {
                depth--;
                if (depth == 0) return i;
                i++;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** Reads a string literal at {@code start} (which must be a quote). Returns [value, nextIndex] or null. */
    private static String[] readStringLiteral(String s, int start) {
        StringBuilder out = new StringBuilder();
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                out.append(s.charAt(i + 1));
                i += 2;
            } else if (c == '"') {
                return new String[] { out.toString(), String.valueOf(i + 1) };
            } else {
                out.append(c);
                i++;
            }
        }
        return null;
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }
}
