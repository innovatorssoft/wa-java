package id.jawa.feature.messaging;

import id.jawa.event.ConnectionUpdate;

/**
 * Baileys {@code fetchAccountReachoutTimelock} request model and fetch
 * result parser.
 *
 * <p>Baileys source: {@code lib/Socket/socket.js}
 * ({@code fetchAccountReachoutTimelock}) calls
 * {@code executeWMexQuery({}, QueryIds.REACHOUT_TIMELOCK,
 * XWAPaths.xwa2_fetch_account_reachout_timelock, ...)} — empty
 * variables, query id and data path verbatim from
 * {@code lib/Types/Mex.js} — then maps the result:
 * {@code isActive: !!is_active},
 * {@code timeEnforcementEnds: time_enforcement_ends && != '0'
 * ? new Date(parseInt(...) * 1000) : undefined},
 * {@code enforcementType: enforcement_type ?? DEFAULT}, and emits
 * {@code connection.update} with {@code {reachoutTimeLock: result}}.
 * The MEX transport itself is {@code lib/Socket/mex.js}:
 * {@code executeWMexQuery} parses the {@code <result>} JSON, throws on
 * a non-empty {@code errors} array, and returns
 * {@code data[dataPath]}.
 *
 * <p>Fetch-path semantics differ deliberately from the notification
 * path ({@link id.jawa.event.MexNotificationParser}): the notification
 * parser defaults a missing expiry to now+60s and validates the
 * enforcement type against the known enum, falling back to
 * {@code DEFAULT} for unknown values. The fetch path does neither —
 * a missing or {@code '0'} expiry is absent (null here, mirroring JS
 * {@code undefined}), and an enforcement type present in the payload
 * is carried verbatim, with {@code DEFAULT} only when the field is
 * absent. This parser follows the fetch path, not the notification
 * path, and reuses the existing
 * {@link ConnectionUpdate.ReachoutTimeLock} value shape (epoch
 * milliseconds, null when absent).
 *
 * <p>Wa-java has no JSON library, so parsing is string-scanning scoped
 * to this payload shape, in the same style as the Increment 3/4 mex
 * parsers. The input is the raw MEX result JSON string as returned by
 * {@code JaWaClient.sendMexQuery} (the {@code <result>} node text,
 * envelope included). Returns null when the data-path object is
 * absent, when the JSON carries a non-empty {@code errors} array
 * (Baileys throws there), or when the input is null/blank — the
 * caller must then fail without emitting {@code connection.update},
 * exactly as Baileys throws before its emit.
 */
public final class ReachoutTimelock {

    /** Baileys {@code QueryIds.REACHOUT_TIMELOCK} (lib/Types/Mex.js), verbatim — not invented. */
    public static final String QUERY_ID = "23983697327930364";
    /** Baileys {@code XWAPaths.xwa2_fetch_account_reachout_timelock} (lib/Types/Mex.js). */
    public static final String DATA_PATH = "xwa2_fetch_account_reachout_timelock";
    /** Baileys fetch variables: empty object. */
    public static final String VARIABLES_JSON = "{}";
    /** Baileys fetch fallback: {@code enforcement_type ?? DEFAULT}. */
    public static final String DEFAULT_ENFORCEMENT_TYPE = "DEFAULT";

    private ReachoutTimelock() {}

    /**
     * Parses a fetch-account-reachout-timelock MEX response into the
     * shared {@link ConnectionUpdate.ReachoutTimeLock} shape.
     *
     * @param mexResultJson raw MEX result JSON (envelope included), or null
     * @return parsed timelock, or null when the payload is absent, carries
     *         GraphQL errors, or cannot be parsed
     */
    public static ConnectionUpdate.ReachoutTimeLock parse(String mexResultJson) {
        if (mexResultJson == null || mexResultJson.isBlank()) return null;
        if (hasNonEmptyErrors(mexResultJson)) return null;
        String payload = extractObject(mexResultJson, DATA_PATH);
        if (payload == null) return null;
        boolean isActive = Boolean.TRUE.equals(extractBoolean(payload, "is_active"))
            || "true".equalsIgnoreCase(extractRaw(payload, "is_active"));
        Long ends = null;
        String endsRaw = extractRaw(payload, "time_enforcement_ends");
        if (endsRaw != null && !"0".equals(endsRaw.trim())) {
            try {
                ends = Long.parseLong(endsRaw.trim()) * 1000L;
            } catch (NumberFormatException ignored) {
                // Baileys would produce an invalid Date; wa-java carries null instead.
                ends = null;
            }
        }
        String enforcement = extractString(payload, "enforcement_type");
        if (enforcement == null) enforcement = DEFAULT_ENFORCEMENT_TYPE;
        return new ConnectionUpdate.ReachoutTimeLock(isActive, ends, enforcement);
    }

    /** Whether the JSON carries a non-empty {@code errors} array (Baileys throws on this). */
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

    /** Balanced {@code {...}} object for {@code "field": {...}}, or null. */
    private static String extractObject(String json, String field) {
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int brace = json.indexOf('{', colon);
        if (brace < 0) return null;
        int depth = 0;
        int i = brace;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '"') {
                int next = skipString(json, i);
                if (next < 0) return null;
                i = next;
            } else if (c == '{') {
                depth++;
                i++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return json.substring(brace, i + 1);
                i++;
            } else {
                i++;
            }
        }
        return null;
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

    /** First quoted-string value for the field, or null when absent or not a string. */
    private static String extractString(String json, String field) {
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int i = skipWs(json, colon + 1);
        if (i >= json.length() || json.charAt(i) != '"') return null;
        int next = skipString(json, i);
        if (next < 0) return null;
        return unescape(json.substring(i + 1, next - 1));
    }

    /** Raw scalar value for the field: quoted string content or bare number/literal, or null. */
    private static String extractRaw(String json, String field) {
        String asString = extractString(json, field);
        if (asString != null) return asString;
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + needle.length());
        if (colon < 0) return null;
        int start = skipWs(json, colon + 1);
        int end = start;
        while (end < json.length()
            && json.charAt(end) != ',' && json.charAt(end) != '}' && json.charAt(end) != ']') {
            end++;
        }
        String raw = json.substring(start, end).trim();
        return raw.isEmpty() ? null : raw;
    }

    /** Index just past the closing quote of the string literal at {@code start}, or -1. */
    private static int skipString(String s, int start) {
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) i += 2;
            else if (c == '"') return i + 1;
            else i++;
        }
        return -1;
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                out.append(s.charAt(i + 1));
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }
}
