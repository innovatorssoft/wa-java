package id.ja_wa.feature.newsletter;

import id.ja_wa.domain.model.NewsletterMetadata;

/**
 * Pure builders for WhatsApp newsletter (channel) MEX GraphQL requests.
 *
 * <p>Baileys source: {@code lib/Socket/newsletter.js} (all operations go
 * through {@code executeWMexQuery}, i.e. the {@code w:mex} IQ in
 * {@code lib/Socket/mex.js}) and {@code lib/Types/Mex.js} for the query
 * ids and data paths. The query ids below are copied verbatim from
 * {@code lib/Types/Mex.js}; they are <em>not</em> the ids some older
 * wa-java call sites use for follow/unfollow/mute, which pre-date this
 * class and are left untouched.
 *
 * <p>A {@link MexRequest} is only the request model: it carries the
 * {@code query_id} and the JSON {@code variables} object that the
 * {@code w:mex} IQ must carry. Sending is done by the caller through the
 * existing {@code JaWaClient.sendMexQuery} transport (see
 * {@link id.ja_wa.client.NewsletterService}); this class performs no I/O
 * and never claims a request was sent.
 *
 * <p>No newsletter <em>promote</em> builder exists here, deliberately:
 * Baileys {@code lib/Socket/newsletter.js} defines {@code newsletterDemote}
 * and {@code newsletterChangeOwner} but no promote operation. Promote only
 * appears as an <em>inbound</em> notification
 * ({@code NotificationNewsletterAdminPromote} in
 * {@code lib/Socket/messages-recv.js}). Inventing a promote query id would
 * be fabrication, so promote is documented-as-blocked, not implemented.
 */
public final class NewsletterMexQuery {

    private NewsletterMexQuery() {}

    /** One MEX request: the query id, the response data path, and the JSON variables object. */
    public record MexRequest(String queryId, String dataPath, String jsonVariables) {}

    // Query ids and data paths, verbatim from Baileys lib/Types/Mex.js.
    public static final String QUERY_CREATE = "8823471724422422";
    public static final String QUERY_UPDATE_METADATA = "24250201037901610";
    public static final String QUERY_METADATA = "6563316087068696";
    public static final String QUERY_SUBSCRIBERS = "9783111038412085";
    public static final String QUERY_SUBSCRIBED = "6388546374527196";
    public static final String QUERY_FOLLOW = "24404358912487870";
    public static final String QUERY_UNFOLLOW = "9767147403369991";
    public static final String QUERY_MUTE = "29766401636284406";
    public static final String QUERY_UNMUTE = "9864994326891137";
    public static final String QUERY_ADMIN_COUNT = "7130823597031706";
    public static final String QUERY_CHANGE_OWNER = "7341777602580933";
    public static final String QUERY_DEMOTE = "6551828931592903";
    public static final String QUERY_DELETE = "30062808666639665";
    /** Baileys {@code lib/Socket/socket.js} {@code fetchNewChatMessageCap}. */
    public static final String QUERY_MESSAGE_CAPPING_INFO = "24503548349331633";

    public static final String PATH_CREATE = "xwa2_newsletter_create";
    public static final String PATH_SUBSCRIBERS = "xwa2_newsletter_subscribers";
    public static final String PATH_SUBSCRIBED = "xwa2_newsletter_subscribed";
    public static final String PATH_ADMIN = "xwa2_newsletter_admin";
    public static final String PATH_CHANGE_OWNER = "xwa2_newsletter_change_owner";
    public static final String PATH_DEMOTE = "xwa2_newsletter_demote";
    public static final String PATH_DELETE = "xwa2_newsletter_delete_v2";
    public static final String PATH_MESSAGE_CAPPING_INFO = "xwa2_message_capping_info";

    /**
     * Baileys {@code newsletterCreate}: {@code {input: {name, description ?? null}}}.
     */
    public static MexRequest create(String name, String description) {
        requireNonBlank(name, "name");
        String vars = "{\"input\":{\"name\":" + jsonString(name)
            + ",\"description\":" + (description == null ? "null" : jsonString(description)) + "}}";
        return new MexRequest(QUERY_CREATE, PATH_CREATE, vars);
    }

    /**
     * Baileys {@code newsletterUpdate}: {@code {newsletter_id, updates: {..., settings: null}}}.
     * Only non-null update fields are included; {@code settings} is always
     * {@code null}, exactly as Baileys sends it.
     */
    public static MexRequest update(String newsletterJid, String name, String description, String pictureBase64) {
        requireNonBlank(newsletterJid, "newsletterJid");
        StringBuilder updates = new StringBuilder();
        if (name != null) updates.append("\"name\":").append(jsonString(name)).append(',');
        if (description != null) updates.append("\"description\":").append(jsonString(description)).append(',');
        if (pictureBase64 != null) updates.append("\"picture\":").append(jsonString(pictureBase64)).append(',');
        updates.append("\"settings\":null");
        return new MexRequest(QUERY_UPDATE_METADATA, "xwa2_newsletter_update",
            "{\"newsletter_id\":" + jsonString(newsletterJid)
                + ",\"updates\":{" + updates + "}}");
    }

    /** Baileys {@code newsletterUpdateName}. */
    public static MexRequest updateName(String newsletterJid, String name) {
        return update(newsletterJid, name, null, null);
    }

    /** Baileys {@code newsletterUpdateDescription}. */
    public static MexRequest updateDescription(String newsletterJid, String description) {
        return update(newsletterJid, null, description, null);
    }

    /**
     * Baileys {@code newsletterRemovePicture}: update with an empty picture string
     * (not null — null would omit the field).
     */
    public static MexRequest removePicture(String newsletterJid) {
        return update(newsletterJid, null, null, "");
    }

    /** Baileys {@code newsletterSubscribers}. */
    public static MexRequest subscribers(String newsletterJid) {
        requireNonBlank(newsletterJid, "newsletterJid");
        return new MexRequest(QUERY_SUBSCRIBERS, PATH_SUBSCRIBERS,
            "{\"newsletter_id\":" + jsonString(newsletterJid) + "}");
    }

    /** Baileys {@code newsletterSubscribed} (all subscribed newsletters). */
    public static MexRequest subscribed() {
        return new MexRequest(QUERY_SUBSCRIBED, PATH_SUBSCRIBED, "{}");
    }

    /** Baileys {@code newsletterAdminCount}; the response carries {@code admin_count}. */
    public static MexRequest adminCount(String newsletterJid) {
        requireNonBlank(newsletterJid, "newsletterJid");
        return new MexRequest(QUERY_ADMIN_COUNT, PATH_ADMIN,
            "{\"newsletter_id\":" + jsonString(newsletterJid) + "}");
    }

    /** Baileys {@code newsletterChangeOwner}. */
    public static MexRequest changeOwner(String newsletterJid, String newOwnerJid) {
        requireNonBlank(newsletterJid, "newsletterJid");
        requireNonBlank(newOwnerJid, "newOwnerJid");
        return new MexRequest(QUERY_CHANGE_OWNER, PATH_CHANGE_OWNER,
            "{\"newsletter_id\":" + jsonString(newsletterJid)
                + ",\"user_id\":" + jsonString(newOwnerJid) + "}");
    }

    /** Baileys {@code newsletterDemote}. There is no Baileys promote counterpart (see class Javadoc). */
    public static MexRequest demote(String newsletterJid, String userJid) {
        requireNonBlank(newsletterJid, "newsletterJid");
        requireNonBlank(userJid, "userJid");
        return new MexRequest(QUERY_DEMOTE, PATH_DEMOTE,
            "{\"newsletter_id\":" + jsonString(newsletterJid)
                + ",\"user_id\":" + jsonString(userJid) + "}");
    }

    /** Baileys {@code newsletterDelete}. */
    public static MexRequest delete(String newsletterJid) {
        requireNonBlank(newsletterJid, "newsletterJid");
        return new MexRequest(QUERY_DELETE, PATH_DELETE,
            "{\"newsletter_id\":" + jsonString(newsletterJid) + "}");
    }

    /**
     * Baileys {@code lib/Socket/socket.js} {@code fetchNewChatMessageCap}:
     * {@code {input: {type: 'INDIVIDUAL_NEW_CHAT_MSG'}}}. Baileys returns
     * the MEX result object verbatim and emits it as
     * {@code message-capping.update} from the notification path; no field
     * list is derivable from the Baileys source, so callers receive the raw
     * JSON string and must not invent fields from it.
     */
    public static MexRequest messageCappingInfo() {
        return new MexRequest(QUERY_MESSAGE_CAPPING_INFO, PATH_MESSAGE_CAPPING_INFO,
            "{\"input\":{\"type\":\"INDIVIDUAL_NEW_CHAT_MSG\"}}");
    }

    /**
     * Parses a newsletter-create MEX response into {@link NewsletterMetadata}.
     * Mirrors Baileys {@code parseNewsletterCreateResponse} in
     * {@code lib/Socket/newsletter.js}: {@code id}, {@code thread_metadata}
     * ({@code name.text}, {@code creation_time}, {@code description.text},
     * {@code invite}, {@code subscribers_count}, {@code picture{id, direct_path}})
     * and {@code viewer_metadata.mute}. The input is the raw MEX JSON string
     * as returned by the {@code w:mex} result node (envelope included); field
     * extraction is string-scanning in the same style as the existing
     * newsletter JSON parsing in JaWaClient, because wa-java has no JSON
     * library dependency. Returns {@code null} for null/blank input or when
     * no {@code id} can be found. {@code role} is always null: the create
     * response carries no role.
     */
    public static NewsletterMetadata parseCreateResponse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return null;
        String id = extractString(rawJson, "id");
        if (id == null) return null;
        String name = extractNestedText(rawJson, "name");
        String description = extractNestedText(rawJson, "description");
        String invite = extractString(rawJson, "invite");
        String pictureUrl = extractNestedText(rawJson, "picture", "direct_path");
        String mute = extractString(rawJson, "mute");
        long creationTime = parseLong(extractString(rawJson, "creation_time"), 0L);
        int subscribers = (int) parseLong(extractString(rawJson, "subscribers_count"), 0L);
        return new NewsletterMetadata(id, name, description, invite, creationTime,
            subscribers, null, "ON".equalsIgnoreCase(mute), pictureUrl);
    }

    /**
     * Extracts the {@code admin_count} number from a newsletter-admin-count
     * MEX response, or {@code null} when absent/unparseable.
     */
    public static Integer parseAdminCount(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return null;
        String raw = extractString(rawJson, "admin_count");
        if (raw == null) return null;
        try {
            return Integer.parseInt(raw.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** JSON string literal for {@code value}, with escaping. Never returns null. */
    public static String jsonString(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    /** First string-or-number value for {@code "field": value} anywhere in the JSON text, or null. */
    static String extractString(String json, String field) {
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;
        if (json.charAt(start) == '"') {
            StringBuilder out = new StringBuilder();
            for (int i = start + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    out.append(json.charAt(i + 1));
                    i++;
                } else if (c == '"') {
                    return out.toString();
                } else {
                    out.append(c);
                }
            }
            return null;
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ']') end++;
        String raw = json.substring(start, end).trim();
        return raw.isEmpty() ? null : raw;
    }

    /** {@code "outer": {"text": ...}} value, i.e. Baileys {@code thread.name.text} style. */
    static String extractNestedText(String json, String outer) {
        return extractNestedText(json, outer, "text");
    }

    static String extractNestedText(String json, String outer, String inner) {
        String needle = "\"" + outer + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int brace = json.indexOf('{', colon);
        if (brace < 0) return null;
        // Search for the inner field within the outer object only.
        int depth = 0;
        int end = brace;
        for (; end < json.length(); end++) {
            char c = json.charAt(end);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) break;
            }
        }
        if (end >= json.length()) return null;
        return extractString(json.substring(brace, end + 1), inner);
    }

    static long parseLong(String raw, long fallback) {
        if (raw == null) return fallback;
        try {
            return Long.parseLong(raw.replaceAll("[^0-9-]", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
