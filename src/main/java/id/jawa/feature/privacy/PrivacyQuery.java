package id.jawa.feature.privacy;

import id.jawa.domain.model.Jid;
import id.jawa.protocol.codec.BinaryNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure builders (and the fetch parser) for WhatsApp privacy IQs.
 *
 * <p>Baileys source: {@code lib/Socket/chats.js}
 * ({@code fetchPrivacySettings}, {@code privacyQuery} and the
 * {@code update*Privacy} wrappers, plus
 * {@code updateDefaultDisappearingMode}). The pre-existing
 * {@code JaWaClient.getPrivacySettings} /
 * {@code setPrivacySetting} / {@code setDefaultDisappearingTimer}
 * already send exactly these shapes inline; this class factors the
 * shapes out so they are unit-testable without a socket and so the
 * Increment 7 typed facades ({@code updateMessagesPrivacy} et al.)
 * share one definition of each IQ instead of duplicating node
 * construction. Builders perform no I/O.
 *
 * <p>Naming reconciliation (no renames, per the additive-only rule):
 * wa-java's pre-existing {@code getPrivacySettings} is Baileys'
 * {@code fetchPrivacySettings} without the cache (it fetches every
 * time); {@code JaWaClient.fetchPrivacySettings(boolean)} is the
 * cached Baileys-faithful form. {@code setPrivacySetting} is
 * Baileys' {@code privacyQuery}; the typed {@code update*Privacy}
 * methods are thin facades over it with Baileys' category names.
 * {@code setDefaultDisappearingTimer} is Baileys'
 * {@code updateDefaultDisappearingMode}; the Baileys-named method
 * delegates to it.
 *
 * <p>Category names are Baileys' verbatim {@code privacyQuery} names:
 * {@code messages}, {@code calladd}, {@code last}, {@code online},
 * {@code profile}, {@code status}, {@code readreceipts},
 * {@code groupadd}. Values are passed through verbatim (Baileys
 * takes no enum): {@code all}, {@code contacts},
 * {@code contact_blacklist}, {@code none}. wa-java defines no value
 * enum, matching the pre-existing generic setter.
 */
public final class PrivacyQuery {

    private PrivacyQuery() {}

    /** Baileys {@code privacyQuery('messages', value)}. */
    public static final String CATEGORY_MESSAGES = "messages";
    /** Baileys {@code privacyQuery('calladd', value)}. */
    public static final String CATEGORY_CALL_ADD = "calladd";
    /** Baileys {@code privacyQuery('last', value)}. */
    public static final String CATEGORY_LAST_SEEN = "last";
    /** Baileys {@code privacyQuery('online', value)}. */
    public static final String CATEGORY_ONLINE = "online";
    /** Baileys {@code privacyQuery('profile', value)}. */
    public static final String CATEGORY_PROFILE_PICTURE = "profile";
    /** Baileys {@code privacyQuery('status', value)}. */
    public static final String CATEGORY_STATUS = "status";
    /** Baileys {@code privacyQuery('readreceipts', value)}. */
    public static final String CATEGORY_READ_RECEIPTS = "readreceipts";
    /** Baileys {@code privacyQuery('groupadd', value)}. */
    public static final String CATEGORY_GROUP_ADD = "groupadd";

    /**
     * Baileys {@code fetchPrivacySettings}: get IQ,
     * {@code xmlns=privacy}, to {@code s.whatsapp.net}, with a bare
     * {@code <privacy/>} child.
     */
    public static BinaryNode buildFetch(String iqId) {
        require(iqId, "iqId");
        return new BinaryNode("iq", Map.of(
            "id", iqId,
            "to", Jid.SERVER_WHATSAPP,
            "type", "get",
            "xmlns", "privacy"
        ), List.of(new BinaryNode("privacy", Map.of(), null)));
    }

    /**
     * Parses a fetch response into the category map, mirroring
     * Baileys' {@code reduceBinaryNodeToDictionary(content?.[0],
     * 'category')} (the {@code <privacy>} child, keyed by each
     * {@code <category>} child's {@code name} attr, valued by its
     * {@code value} attr). Rows missing either attr are skipped.
     * Returns an empty map for a null response or no
     * {@code <privacy>} child.
     */
    public static Map<String, String> parseFetch(BinaryNode resultIq) {
        Map<String, String> map = new LinkedHashMap<>();
        if (resultIq == null) return map;
        BinaryNode privacy = resultIq.child("privacy");
        if (privacy == null) return map;
        for (BinaryNode child : privacy.childrenList()) {
            if (!"category".equals(child.tag())) continue;
            String name = child.attr("name");
            String value = child.attr("value");
            if (name != null && value != null) map.put(name, value);
        }
        return map;
    }

    /**
     * Baileys {@code privacyQuery(name, value)}: set IQ,
     * {@code xmlns=privacy}, to {@code s.whatsapp.net}, carrying
     * {@code <privacy><category name value/>}.
     */
    public static BinaryNode buildUpdate(String iqId, String category, String value) {
        require(iqId, "iqId");
        require(category, "category");
        require(value, "value");
        BinaryNode cat = new BinaryNode("category", Map.of("name", category, "value", value), null);
        BinaryNode privacy = new BinaryNode("privacy", Map.of(), List.of(cat));
        return new BinaryNode("iq", Map.of(
            "id", iqId,
            "to", Jid.SERVER_WHATSAPP,
            "type", "set",
            "xmlns", "privacy"
        ), List.of(privacy));
    }

    /**
     * Baileys {@code updateDefaultDisappearingMode}: set IQ,
     * {@code xmlns=disappearing_mode}, to {@code s.whatsapp.net},
     * carrying {@code <disappearing_mode duration="..."/>} with the
     * duration as a decimal string.
     */
    public static BinaryNode buildUpdateDefaultDisappearingMode(String iqId, int durationSeconds) {
        require(iqId, "iqId");
        BinaryNode mode = new BinaryNode("disappearing_mode",
            Map.of("duration", String.valueOf(durationSeconds)), null);
        return new BinaryNode("iq", Map.of(
            "id", iqId,
            "to", Jid.SERVER_WHATSAPP,
            "type", "set",
            "xmlns", "disappearing_mode"
        ), List.of(mode));
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
