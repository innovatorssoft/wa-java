package id.jawa.feature.store;

import id.jawa.event.ContactsUpsert;
import id.jawa.event.GroupsUpsert;
import id.jawa.event.LabelsAssociation;
import id.jawa.event.LabelsEdit;
import id.jawa.event.PresenceInfo;
import id.jawa.feature.messaging.MessageReceiver;
import id.jawa.proto.Wa;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * JSON serialisation for {@link InMemoryStore}, mirroring Baileys'
 * {@code makeInMemoryStore} {@code toJSON}/{@code fromJSON}
 * ({@code lib/Store/make-in-memory-store.js}).
 * <p>
 * Baileys serialises {@code {chats, contacts, messages, labels,
 * labelAssociations}} (its {@code messages} are full
 * {@code WebMessageInfo} protos). This format covers everything wa-java's
 * store holds, with the same shapes where the models line up, plus the
 * fields Baileys keeps separately: {@code connectionState} (its
 * {@code state.connection}), LID mappings, reactions, presences and the
 * per-chat message cap. Stored messages are wa-java's
 * {@link MessageReceiver.Decoded} records, whose proto payload is carried
 * as base64 {@code Wa.Message} bytes — so a round-trip preserves the
 * decoded text/interactive view as well as the exact proto.
 * <p>
 * No JSON library is on wa-java's classpath (see {@code pom.xml}), and
 * this increment adds no dependencies, so the writer and the small
 * recursive-descent parser below are hand-written and limited to the
 * JSON this writer produces plus ordinary JSON values.
 * <p>
 * Failure policy — deliberately more lenient than Baileys, whose
 * {@code fromJSON} assumes well-formed input: the store is a
 * <em>cache</em> rebuildable from history sync, not credentials, so
 * {@link #fromJson(String)} returns an empty store for null, blank or
 * malformed input instead of throwing, and skips individual messages
 * whose proto bytes no longer parse. A caller that must distinguish
 * "empty file" from "corrupt file" should inspect the input itself
 * before calling this method.
 */
public final class StoreJson {

    /** Format version written by {@link #toJson(InMemoryStore)}. */
    public static final int FORMAT_VERSION = 1;

    private StoreJson() {}

    /**
     * Serialises a store to JSON.
     *
     * @param store store to serialise; must not be null
     * @return JSON string
     */
    public static String toJson(InMemoryStore store) {
        if (store == null) throw new IllegalArgumentException("store must not be null");
        StringBuilder sb = new StringBuilder();
        sb.append("{\"version\":").append(FORMAT_VERSION);
        sb.append(",\"maxMessagesPerChat\":").append(store.maxMessagesPerChat());
        sb.append(",\"connectionState\":").append(str(store.connectionState()));
        sb.append(",\"chats\":[");
        boolean first = true;
        for (InMemoryStore.StoredChat c : store.chats()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(str(c.id()))
                .append(",\"lastMessageTimestamp\":").append(c.lastMessageTimestamp())
                .append(",\"unreadCount\":").append(c.unreadCount())
                .append(",\"pinned\":").append(c.pinned())
                .append(",\"archived\":").append(c.archived()).append('}');
        }
        sb.append("],\"messages\":{");
        first = true;
        for (String chatId : new TreeMap<>(store.messageCounts()).keySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(str(chatId)).append(":[");
            boolean firstMsg = true;
            for (MessageReceiver.Decoded d : store.messages(chatId)) {
                if (!firstMsg) sb.append(',');
                firstMsg = false;
                sb.append("{\"senderJid\":").append(str(d.senderJid()))
                    .append(",\"groupJid\":").append(str(d.groupJid()))
                    .append(",\"msgId\":").append(str(d.msgId()))
                    .append(",\"encType\":").append(str(d.encType()))
                    .append(",\"messageBase64\":")
                    .append(d.message() == null ? "null"
                        : str(Base64.getEncoder().encodeToString(d.message().toByteArray())))
                    .append(",\"text\":").append(str(d.text()));
                if (d.interactive() == null) {
                    sb.append(",\"interactive\":null");
                } else {
                    sb.append(",\"interactive\":{\"kind\":").append(str(d.interactive().kind()))
                        .append(",\"selectedId\":").append(str(d.interactive().selectedId()))
                        .append(",\"paramsJson\":").append(str(d.interactive().paramsJson()))
                        .append(",\"displayText\":").append(str(d.interactive().displayText()))
                        .append('}');
                }
                sb.append(",\"timestamp\":").append(d.timestamp()).append('}');
            }
            sb.append(']');
        }
        sb.append("},\"contacts\":[");
        first = true;
        for (ContactsUpsert.Contact c : store.contacts()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(str(c.id()))
                .append(",\"name\":").append(str(c.name()))
                .append(",\"username\":").append(str(c.username()))
                .append(",\"lid\":").append(str(c.lid()))
                .append(",\"phoneNumber\":").append(str(c.phoneNumber()))
                .append(",\"imgUrl\":").append(str(c.imgUrl())).append('}');
        }
        sb.append("],\"groups\":[");
        first = true;
        for (GroupsUpsert.GroupInfo g : store.groups()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(str(g.id()))
                .append(",\"subject\":").append(str(g.subject()))
                .append(",\"author\":").append(str(g.author()))
                .append(",\"authorPn\":").append(str(g.authorPn()))
                .append(",\"authorUsername\":").append(str(g.authorUsername()))
                .append(",\"participantJids\":").append(strList(g.participantJids())).append('}');
        }
        sb.append("],\"lidToPn\":{");
        first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(store.lidMappings()).entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(str(e.getKey())).append(':').append(str(e.getValue()));
        }
        sb.append("},\"reactions\":[");
        first = true;
        for (InMemoryStore.ReactionEntry r : store.reactions()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"chatId\":").append(str(r.chatId()))
                .append(",\"messageId\":").append(str(r.messageId()))
                .append(",\"code\":").append(str(r.code())).append('}');
        }
        sb.append("],\"presences\":{");
        first = true;
        // Presence chats are keyed independently of message chats, so they
        // are listed via presenceChatIds() (presence-only chats included).
        for (String chatId : store.presenceChatIds()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(str(chatId)).append(":{");
            boolean firstP = true;
            for (Map.Entry<String, PresenceInfo> e
                    : new TreeMap<>(store.presences(chatId)).entrySet()) {
                if (!firstP) sb.append(',');
                firstP = false;
                sb.append(str(e.getKey())).append(":{")
                    .append("\"lastKnownPresence\":").append(str(e.getValue().lastKnownPresence()))
                    .append(",\"lastSeen\":")
                    .append(e.getValue().lastSeen() == null ? "null" : e.getValue().lastSeen().toString())
                    .append(",\"groupOnlineCount\":")
                    .append(e.getValue().groupOnlineCount() == null ? "null"
                        : e.getValue().groupOnlineCount().toString())
                    .append('}');
            }
            sb.append('}');
        }
        sb.append("},\"labels\":[");
        first = true;
        for (LabelsEdit l : store.labels()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(str(l.id()))
                .append(",\"name\":").append(str(l.name()))
                .append(",\"color\":").append(str(l.color()))
                .append(",\"deleted\":").append(l.deleted())
                .append(",\"predefinedId\":").append(str(l.predefinedId())).append('}');
        }
        sb.append("],\"labelAssociations\":[");
        first = true;
        for (LabelsAssociation a : store.allLabelAssociations()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"type\":").append(str(a.type()))
                .append(",\"associationType\":").append(str(a.associationType()))
                .append(",\"chatId\":").append(str(a.chatId()))
                .append(",\"labelId\":").append(str(a.labelId()))
                .append(",\"messageId\":").append(str(a.messageId())).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Deserialises a store. Null, blank or malformed input yields an empty
     * store (see the class Javadoc for why); valid input restores chats,
     * messages, contacts, groups, LID mappings, reactions, presences,
     * labels and label associations without emitting any events.
     *
     * @param json JSON produced by {@link #toJson(InMemoryStore)}, or null/blank/malformed
     * @return restored store, or an empty store for unusable input
     */
    @SuppressWarnings("unchecked")
    public static InMemoryStore fromJson(String json) {
        if (json == null || json.isBlank()) return new InMemoryStore();
        Object parsed;
        try {
            parsed = new JsonParser(json).parseValue();
        } catch (RuntimeException e) {
            return new InMemoryStore();
        }
        if (!(parsed instanceof Map<?, ?> root)) return new InMemoryStore();
        Map<String, Object> r = (Map<String, Object>) root;
        int cap = (int) longOr(r.get("maxMessagesPerChat"), InMemoryStore.DEFAULT_MAX_MESSAGES_PER_CHAT);
        if (cap <= 0) cap = InMemoryStore.DEFAULT_MAX_MESSAGES_PER_CHAT;
        InMemoryStore store = new InMemoryStore(cap);
        store.restoreConnectionState(strOr(r.get("connectionState"), "close"));
        for (Object o : listOr(r.get("chats"))) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> c = (Map<String, Object>) m;
            store.restoreChat(new InMemoryStore.StoredChat(
                strOr(c.get("id"), null),
                longOr(c.get("lastMessageTimestamp"), 0L),
                (int) longOr(c.get("unreadCount"), 0L),
                boolOr(c.get("pinned"), false),
                boolOr(c.get("archived"), false)));
        }
        if (r.get("messages") instanceof Map<?, ?> msgs) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) msgs).entrySet()) {
                String chatId = String.valueOf(e.getKey());
                for (Object o : listOr(e.getValue())) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    Map<String, Object> d = (Map<String, Object>) m;
                    Wa.Message proto = null;
                    String b64 = strOr(d.get("messageBase64"), null);
                    if (b64 != null) {
                        try {
                            proto = Wa.Message.parseFrom(Base64.getDecoder().decode(b64));
                        } catch (RuntimeException | java.io.IOException ignored) {
                            continue; // skip one undecodable message, keep the rest
                        }
                    }
                    MessageReceiver.InteractiveResponse interactive = null;
                    if (d.get("interactive") instanceof Map<?, ?> im) {
                        Map<String, Object> ii = (Map<String, Object>) im;
                        interactive = new MessageReceiver.InteractiveResponse(
                            strOr(ii.get("kind"), null), strOr(ii.get("selectedId"), null),
                            strOr(ii.get("paramsJson"), null), strOr(ii.get("displayText"), null));
                    }
                    store.restoreMessage(chatId, new MessageReceiver.Decoded(
                        strOr(d.get("senderJid"), null), strOr(d.get("groupJid"), null),
                        strOr(d.get("msgId"), null), strOr(d.get("encType"), null),
                        proto, strOr(d.get("text"), null), interactive,
                        longOr(d.get("timestamp"), 0L)));
                }
            }
        }
        for (Object o : listOr(r.get("contacts"))) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> c = (Map<String, Object>) m;
            store.restoreContact(new ContactsUpsert.Contact(
                strOr(c.get("id"), null), strOr(c.get("name"), null),
                strOr(c.get("username"), null), strOr(c.get("lid"), null),
                strOr(c.get("phoneNumber"), null), strOr(c.get("imgUrl"), null)));
        }
        for (Object o : listOr(r.get("groups"))) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> c = (Map<String, Object>) m;
            List<String> participants = new ArrayList<>();
            for (Object p : listOr(c.get("participantJids"))) participants.add(String.valueOf(p));
            store.restoreGroup(new GroupsUpsert.GroupInfo(
                strOr(c.get("id"), null), strOr(c.get("subject"), null),
                strOr(c.get("author"), null), strOr(c.get("authorPn"), null),
                strOr(c.get("authorUsername"), null), participants));
        }
        if (r.get("lidToPn") instanceof Map<?, ?> lidMap) {
            for (Map.Entry<?, ?> e : lidMap.entrySet()) {
                store.restoreLidMapping(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
        for (Object o : listOr(r.get("reactions"))) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> c = (Map<String, Object>) m;
            store.restoreReaction(strOr(c.get("chatId"), null),
                strOr(c.get("messageId"), null), strOr(c.get("code"), null));
        }
        if (r.get("presences") instanceof Map<?, ?> pres) {
            for (Map.Entry<?, ?> chatEntry : pres.entrySet()) {
                String chatId = String.valueOf(chatEntry.getKey());
                if (!(chatEntry.getValue() instanceof Map<?, ?> perChat)) continue;
                for (Map.Entry<?, ?> pEntry : perChat.entrySet()) {
                    if (!(pEntry.getValue() instanceof Map<?, ?> pm)) continue;
                    Map<String, Object> p = (Map<String, Object>) pm;
                    Long lastSeen = p.get("lastSeen") instanceof Number n ? n.longValue() : null;
                    Integer count = p.get("groupOnlineCount") instanceof Number n
                        ? n.intValue() : null;
                    store.restorePresence(chatId, String.valueOf(pEntry.getKey()),
                        new PresenceInfo(strOr(p.get("lastKnownPresence"), null), lastSeen, count));
                }
            }
        }
        for (Object o : listOr(r.get("labels"))) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> c = (Map<String, Object>) m;
            store.restoreLabel(new LabelsEdit(
                strOr(c.get("id"), null), strOr(c.get("name"), null),
                strOr(c.get("color"), null), boolOr(c.get("deleted"), false),
                strOr(c.get("predefinedId"), null)));
        }
        for (Object o : listOr(r.get("labelAssociations"))) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> c = (Map<String, Object>) m;
            store.restoreLabelAssociation(new LabelsAssociation(
                strOr(c.get("type"), null), strOr(c.get("associationType"), null),
                strOr(c.get("chatId"), null), strOr(c.get("labelId"), null),
                strOr(c.get("messageId"), null)));
        }
        return store;
    }

    private static String str(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String strList(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String v : values) {
            if (!first) sb.append(',');
            first = false;
            sb.append(str(v));
        }
        return sb.append(']').toString();
    }

    private static List<Object> listOr(Object o) {
        return o instanceof List<?> l ? new ArrayList<>(l) : List.of();
    }

    private static long longOr(Object o, long fallback) {
        return o instanceof Number n ? n.longValue() : fallback;
    }

    private static boolean boolOr(Object o, boolean fallback) {
        return o instanceof Boolean b ? b : fallback;
    }

    private static String strOr(Object o, String fallback) {
        return o instanceof String s ? s : fallback;
    }

    /** Minimal JSON parser: objects, arrays, strings, numbers, booleans, null. */
    private static final class JsonParser {
        private final String s;
        private int pos;

        JsonParser(String s) {
            this.s = s;
        }

        Object parseValue() {
            skipWs();
            if (pos >= s.length()) throw new IllegalArgumentException("unexpected end of JSON");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBool();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> out = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek() == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipWs();
                if (peek() != '"') throw new IllegalArgumentException("expected object key at " + pos);
                String key = parseString();
                skipWs();
                if (peek() != ':') throw new IllegalArgumentException("expected ':' at " + pos);
                pos++;
                out.put(key, parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return out;
                } else {
                    throw new IllegalArgumentException("expected ',' or '}' at " + pos);
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> out = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') {
                pos++;
                return out;
            }
            while (true) {
                out.add(parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return out;
                } else {
                    throw new IllegalArgumentException("expected ',' or ']' at " + pos);
                }
            }
        }

        private String parseString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) break;
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new IllegalArgumentException("bad \\u escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Boolean parseBool() {
            if (s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("bad literal at " + pos);
        }

        private Object parseNull() {
            if (s.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("bad literal at " + pos);
        }

        private Number parseNumber() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            if (start == pos) throw new IllegalArgumentException("bad number at " + pos);
            String num = s.substring(start, pos);
            try {
                if (num.contains(".") || num.contains("e") || num.contains("E")) {
                    return Double.parseDouble(num);
                }
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number: " + num, e);
            }
        }

        private void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private char peek() {
            return pos < s.length() ? s.charAt(pos) : '\0';
        }
    }
}
