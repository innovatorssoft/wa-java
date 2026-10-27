package id.jawa.feature.newsletter;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the inbound linked-profiles MEX notification.
 *
 * <p>Baileys source: {@code handleLegacyMexNewsletterNotification} in
 * {@code lib/Socket/messages-recv.js}, operation
 * {@code NotificationLinkedProfilesUpdates}. The payload is
 * {@code data.data.xwa2_notify_linked_profiles} with a {@code jid} (the LID)
 * and {@code added_profiles}, each entry either a bare JID string or an
 * object carrying {@code pn} or {@code jid}; Baileys emits one
 * {@code lid-mapping.update} per (lid, pn) pair.
 *
 * <p>Transport gap, stated plainly: Baileys defines <em>no outbound query</em>
 * for linked profiles — there is no query id or data path for it in
 * {@code lib/Types/Mex.js} — so this class provides only the inbound
 * notification parser. Wiring it to a MEX notification dispatch site in
 * JaWaClient is a parent hook; this class performs no I/O and claims no
 * dispatch. wa-java has no JSON library, so parsing is string-scanning
 * scoped to this one payload shape; anything that does not match it
 * returns {@code null} rather than a guessed mapping.
 */
public final class NewsletterLinkedProfiles {

    private NewsletterLinkedProfiles() {}

    /** One parsed linked-profiles notification: a LID and its added PN JIDs. */
    public record LinkedProfilesUpdate(String lidJid, List<String> addedPnJids) {
        public LinkedProfilesUpdate {
            addedPnJids = addedPnJids == null ? List.of() : List.copyOf(addedPnJids);
        }
    }

    /**
     * Parses the linked-profiles object out of a raw MEX notification JSON
     * string. Returns {@code null} for null/blank input, when the
     * {@code xwa2_notify_linked_profiles} object is absent, or when it has
     * no {@code jid}.
     */
    public static LinkedProfilesUpdate parse(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) return null;
        int objStart = rawJson.indexOf("\"xwa2_notify_linked_profiles\"");
        if (objStart < 0) return null;
        int colon = rawJson.indexOf(':', objStart);
        if (colon < 0) return null;
        int brace = rawJson.indexOf('{', colon);
        if (brace < 0) return null;
        int depth = 0;
        int end = brace;
        for (; end < rawJson.length(); end++) {
            char c = rawJson.charAt(end);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) break;
            }
        }
        if (end >= rawJson.length()) return null;
        String obj = rawJson.substring(brace, end + 1);
        String lid = NewsletterMexQuery.extractString(obj, "jid");
        if (lid == null) return null;
        return new LinkedProfilesUpdate(lid, parseAddedProfiles(obj));
    }

    private static List<String> parseAddedProfiles(String obj) {
        List<String> out = new ArrayList<>();
        int idx = obj.indexOf("\"added_profiles\"");
        if (idx < 0) return out;
        int colon = obj.indexOf(':', idx);
        if (colon < 0) return out;
        int bracket = obj.indexOf('[', colon);
        if (bracket < 0) return out;
        int depth = 0;
        int end = bracket;
        for (; end < obj.length(); end++) {
            char c = obj.charAt(end);
            if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') {
                depth--;
                if (depth == 0) break;
            }
        }
        if (end >= obj.length()) return out;
        String array = obj.substring(bracket, end + 1);
        // Entries are either bare strings ("...@s.whatsapp.net") or objects
        // {"pn": "..."} / {"jid": "..."}. Scan string literals in order,
        // keeping only literals that look like JIDs, in array order.
        int i = 0;
        while (i < array.length()) {
            char c = array.charAt(i);
            if (c == '"') {
                StringBuilder lit = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < array.length()) {
                    char d = array.charAt(i);
                    if (d == '\\' && i + 1 < array.length()) {
                        lit.append(array.charAt(i + 1));
                        i += 2;
                    } else if (d == '"') {
                        closed = true;
                        i++;
                        break;
                    } else {
                        lit.append(d);
                        i++;
                    }
                }
                if (closed) {
                    String value = lit.toString();
                    // Skip the key literals themselves ("pn", "jid").
                    if (!"pn".equals(value) && !"jid".equals(value) && value.contains("@")) {
                        out.add(value);
                    }
                }
            } else {
                i++;
            }
        }
        return out;
    }
}
