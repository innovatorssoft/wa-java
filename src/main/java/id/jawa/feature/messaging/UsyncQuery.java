package id.jawa.feature.messaging;

import id.jawa.client.JaWaClient;
import id.jawa.domain.model.ContactStatus;
import id.jawa.domain.model.DeviceInfo;
import id.jawa.domain.model.Jid;
import id.jawa.domain.model.UserInfo;
import id.jawa.protocol.codec.BinaryNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class UsyncQuery {

    private UsyncQuery() {}

    public static BinaryNode buildDeviceListQuery(String iqId, String sid, Collection<String> targetJids) {
        List<BinaryNode> userNodes = new ArrayList<>(targetJids.size());
        for (String j : targetJids) {
            userNodes.add(new BinaryNode("user", Map.of("jid", j), null));
        }

        BinaryNode usync = new BinaryNode("usync",
            Map.of(
                "sid",     sid,
                "mode",    "query",
                "last",    "true",
                "index",   "0",
                "context", "interactive"
            ),
            List.of(
                new BinaryNode("query", Map.of(), List.of(
                    new BinaryNode("devices", Map.of("version", "2"), null),
                    new BinaryNode("lid", Map.of(), null)
                )),
                new BinaryNode("list", Map.of(), userNodes)
            ));

        return new BinaryNode("iq",
            Map.of(
                "xmlns", "usync",
                "type",  "get",
                "to",    Jid.SERVER_WHATSAPP,
                "id",    iqId
            ),
            List.of(usync));
    }

    public static Map<String, List<DeviceInfo>> parseDeviceListResult(BinaryNode resultIq) {
        if (!"result".equals(resultIq.attr("type"))) {
            throw new IllegalArgumentException("not a usync result iq: " + resultIq);
        }
        BinaryNode usync = resultIq.child("usync");
        if (usync == null) return Map.of();
        BinaryNode list = usync.child("list");
        if (list == null) return Map.of();

        Map<String, List<DeviceInfo>> out = new LinkedHashMap<>();
        for (BinaryNode user : list.children("user")) {
            String jid = user.attr("jid");
            if (jid == null) continue;

            BinaryNode lidNode = user.child("lid");
            if (lidNode != null) {
                String lidVal = lidNode.attr("val");
                if (lidVal != null && !lidVal.isBlank()) {
                    Jid jidParsed = Jid.parse(jid);
                    Jid valParsed = Jid.parse(lidVal);
                    if (jidParsed != null && valParsed != null) {
                        String jidBare = jidParsed.user() + "@" + jidParsed.server();
                        String valBare = valParsed.user() + "@" + valParsed.server();
                        if (jidBare.endsWith("@lid") && valBare.endsWith("@s.whatsapp.net")) {
                            JaWaClient.LID_TO_PN_MAP.put(jidBare, valBare);
                        } else if (jidBare.endsWith("@s.whatsapp.net") && valBare.endsWith("@lid")) {
                            JaWaClient.LID_TO_PN_MAP.put(valBare, jidBare);
                        }
                    }
                }
            }

            BinaryNode devices = user.child("devices");
            BinaryNode deviceList = devices == null ? null : devices.child("device-list");
            List<DeviceInfo> dl = new ArrayList<>();
            if (deviceList != null) {
                for (BinaryNode dev : deviceList.children("device")) {
                    String idStr = dev.attr("id");
                    if (idStr == null) continue;
                    int id = Integer.parseInt(idStr);
                    int keyIndex = parseIntOr(dev.attr("key-index"), 0);
                    boolean hosted = "true".equals(dev.attr("is_hosted"));
                    dl.add(new DeviceInfo(id, keyIndex, hosted));
                }
            }
            out.put(jid, dl);
        }
        return out;
    }

    public static BinaryNode buildContactQuery(String iqId, String sid, Collection<String> phones) {
        List<BinaryNode> userNodes = new ArrayList<>(phones.size());
        for (String phone : phones) {
            if (phone == null || phone.isBlank()) continue;
            String trimmed = phone.trim();
            if (trimmed.endsWith("@" + Jid.SERVER_LID) || trimmed.endsWith("@lid")) {
                userNodes.add(new BinaryNode("user", Map.of("jid", trimmed), List.of(
                    new BinaryNode("contact", Map.of(), null)
                )));
            } else {
                String digitsOnly = trimmed.replaceAll("[^0-9]", "");
                if (digitsOnly.isEmpty()) continue;
                String formattedPhone = "+" + digitsOnly;
                BinaryNode contactChild = BinaryNode.text("contact", Map.of(), formattedPhone);
                userNodes.add(new BinaryNode("user", Map.of(), List.of(contactChild)));
            }
        }

        BinaryNode usync = new BinaryNode("usync",
            Map.of(
                "sid",     sid,
                "mode",    "query",
                "last",    "true",
                "index",   "0",
                "context", "interactive"
            ),
            List.of(
                new BinaryNode("query", Map.of(), List.of(
                    new BinaryNode("contact", Map.of(), null),
                    new BinaryNode("lid", Map.of(), null)
                )),
                new BinaryNode("list", Map.of(), userNodes)
            ));

        return new BinaryNode("iq",
            Map.of(
                "xmlns", "usync",
                "type",  "get",
                "to",    Jid.SERVER_WHATSAPP,
                "id",    iqId
            ),
            List.of(usync));
    }

    public static Map<String, ContactStatus> parseContactResult(BinaryNode resultIq) {
        if (!"result".equals(resultIq.attr("type"))) {
            throw new IllegalArgumentException("not a usync result iq: " + resultIq);
        }
        BinaryNode usync = resultIq.child("usync");
        if (usync == null) return Map.of();
        BinaryNode list = usync.child("list");
        if (list == null) return Map.of();

        Map<String, ContactStatus> out = new LinkedHashMap<>();
        for (BinaryNode user : list.children("user")) {
            BinaryNode contactNode = user.child("contact");
            if (contactNode == null) continue;

            String type = contactNode.attr("type");
            boolean isIn = "in".equals(type);

            String userJid = user.attr("jid");
            String pnJid = user.attr("pn_jid");
            String contactPhone = contactNode.textContent();

            // Record LID to PN mapping if available
            BinaryNode lidNode = user.child("lid");
            String lidVal = lidNode != null ? lidNode.attr("val") : null;
            if (userJid != null && userJid.endsWith("@lid") && pnJid != null && pnJid.endsWith("@s.whatsapp.net")) {
                String bareLid = userJid.contains(":") ? userJid.substring(0, userJid.indexOf(':')) + "@lid" : userJid;
                JaWaClient.LID_TO_PN_MAP.put(bareLid, pnJid);
                JaWaClient.LID_TO_PN_MAP.put(userJid, pnJid);
            } else if (lidVal != null && lidVal.endsWith("@lid") && userJid != null && userJid.endsWith("@s.whatsapp.net")) {
                String bareLid = lidVal.contains(":") ? lidVal.substring(0, lidVal.indexOf(':')) + "@lid" : lidVal;
                JaWaClient.LID_TO_PN_MAP.put(bareLid, userJid);
                JaWaClient.LID_TO_PN_MAP.put(lidVal, userJid);
            } else if (userJid != null && userJid.endsWith("@lid") && contactPhone != null && !contactPhone.isBlank()) {
                String digits = contactPhone.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) {
                    String derivedPn = digits + "@" + Jid.SERVER_WHATSAPP;
                    String bareLid = userJid.contains(":") ? userJid.substring(0, userJid.indexOf(':')) + "@lid" : userJid;
                    JaWaClient.LID_TO_PN_MAP.put(bareLid, derivedPn);
                    JaWaClient.LID_TO_PN_MAP.put(userJid, derivedPn);
                }
            }

            // Determine canonical JID
            String canonicalJid = null;
            if (pnJid != null && !pnJid.isBlank()) {
                canonicalJid = pnJid;
            } else if (userJid != null && userJid.endsWith("@" + Jid.SERVER_WHATSAPP)) {
                canonicalJid = userJid;
            } else if (contactPhone != null && !contactPhone.isBlank()) {
                String digits = contactPhone.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) {
                    canonicalJid = digits + "@" + Jid.SERVER_WHATSAPP;
                }
            } else if (userJid != null && !userJid.isBlank()) {
                canonicalJid = userJid;
            }

            if (canonicalJid == null) {
                continue;
            }

            ContactStatus status = new ContactStatus(canonicalJid, isIn);

            // 1. Primary canonical JID
            out.put(canonicalJid, status);

            // 2. Pure digits from canonical JID
            String digits = canonicalJid.contains("@")
                ? canonicalJid.substring(0, canonicalJid.indexOf('@')).replaceAll("[^0-9]", "")
                : canonicalJid.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                out.put(digits, status);
                out.put("+" + digits, status);
                out.put(digits + "@" + Jid.SERVER_WHATSAPP, status);
            }

            // 3. User JID if different
            if (userJid != null && !userJid.isBlank()) {
                out.put(userJid, status);
            }

            // 4. PN JID if different
            if (pnJid != null && !pnJid.isBlank()) {
                out.put(pnJid, status);
            }

            // 5. Contact phone text if different
            if (contactPhone != null && !contactPhone.isBlank()) {
                String trimmedPhone = contactPhone.trim();
                out.put(trimmedPhone, status);
                String phoneDigits = trimmedPhone.replaceAll("[^0-9]", "");
                if (!phoneDigits.isEmpty()) {
                    out.put(phoneDigits, status);
                    out.put("+" + phoneDigits, status);
                    out.put(phoneDigits + "@" + Jid.SERVER_WHATSAPP, status);
                }
            }
        }
        return out;
    }

    public static BinaryNode buildUserInfoQuery(String iqId, String sid, Collection<String> targetJids) {
        List<BinaryNode> userNodes = new ArrayList<>(targetJids.size());
        for (String j : targetJids) {
            userNodes.add(new BinaryNode("user", Map.of("jid", j), null));
        }

        BinaryNode usync = new BinaryNode("usync",
            Map.of(
                "sid",     sid,
                "mode",    "query",
                "last",    "true",
                "index",   "0",
                "context", "interactive"
            ),
            List.of(
                new BinaryNode("query", Map.of(), List.of(
                    new BinaryNode("business", Map.of(), List.of(new BinaryNode("verified_name", Map.of(), null))),
                    new BinaryNode("status", Map.of(), null),
                    new BinaryNode("picture", Map.of(), null),
                    new BinaryNode("devices", Map.of("version", "2"), null),
                    new BinaryNode("lid", Map.of(), null)
                )),
                new BinaryNode("list", Map.of(), userNodes)
            ));

        return new BinaryNode("iq",
            Map.of(
                "xmlns", "usync",
                "type",  "get",
                "to",    Jid.SERVER_WHATSAPP,
                "id",    iqId
            ),
            List.of(usync));
    }

    public static Map<String, UserInfo> parseUserInfoResult(BinaryNode resultIq) {
        Map<String, UserInfo> map = new LinkedHashMap<>();
        if (!"result".equals(resultIq.attr("type"))) return map;
        BinaryNode usync = resultIq.child("usync");
        if (usync == null) return map;
        BinaryNode list = usync.child("list");
        if (list == null) return map;

        for (BinaryNode user : list.childrenList()) {
            if (!"user".equals(user.tag())) continue;
            String jid = user.attr("jid");
            if (jid == null) continue;

            String statusText = null;
            BinaryNode status = user.child("status");
            if (status != null && status.bytesContent() != null) {
                statusText = new String(status.bytesContent(), StandardCharsets.UTF_8);
            }

            String pictureId = null;
            BinaryNode picture = user.child("picture");
            if (picture != null) {
                pictureId = picture.attr("id");
            }

            String lid = null;
            BinaryNode lidNode = user.child("lid");
            if (lidNode != null) {
                lid = lidNode.attr("val");
            }

            String verifiedName = null;
            BinaryNode biz = user.child("business");
            if (biz != null) {
                BinaryNode vn = biz.child("verified_name");
                if (vn != null) verifiedName = vn.attr("name");
            }

            List<DeviceInfo> devices = new ArrayList<>();
            BinaryNode devNode = user.child("devices");
            if (devNode != null) {
                BinaryNode devList = devNode.child("device-list");
                if (devList != null) {
                    for (BinaryNode d : devList.childrenList()) {
                        if (!"device".equals(d.tag())) continue;
                        String idStr = d.attr("id");
                        String keyIdxStr = d.attr("key-index");
                        int devId = idStr != null ? Integer.parseInt(idStr) : 0;
                        int keyIndex = keyIdxStr != null ? Integer.parseInt(keyIdxStr) : 0;
                        boolean hosted = "true".equals(d.attr("is_hosted"));
                        devices.add(new DeviceInfo(devId, keyIndex, hosted));
                    }
                }
            }

            map.put(jid, new UserInfo(jid, lid, statusText, pictureId, verifiedName, devices));
        }
        return map;
    }

    /**
     * One disappearing-mode result, mirroring Baileys'
     * {@code USyncDisappearingModeProtocol} parser
     * ({@code lib/WAUSync/Protocols/USyncDisappearingModeProtocol.js}):
     * {@code duration} is the {@code duration} attr as a number of
     * seconds, and {@code setAtEpochMs} is the {@code t} attr (epoch
     * seconds) converted to epoch milliseconds (Baileys builds a
     * {@code Date} from {@code +(t || 0) * 1000}).
     *
     * @param durationSeconds disappearing duration in seconds
     * @param setAtEpochMs when the duration was set, epoch milliseconds
     *                     (0 when the server sent no {@code t} attr)
     */
    public record DisappearingDuration(long durationSeconds, long setAtEpochMs) {}

    /**
     * Increment 7: Baileys {@code fetchDisappearingDuration}
     * ({@code lib/Socket/chats.js:185}) as a generic query for
     * {@link JaWaClient#executeUSyncQuery}: one
     * {@code <disappearing_mode/>} query element (Baileys'
     * {@code USyncDisappearingModeProtocol.getQueryElement}) and one
     * bare {@code <user jid="..."/>} node per JID. The user nodes are
     * bare because that protocol's {@code getUserElement} returns
     * null, and they carry the {@code jid} attr because
     * {@code executeUSyncQuery} sets it from the user's id when the
     * user has no phone (Baileys' {@code fetchDisappearingDuration}
     * passes JIDs via {@code withId}, not phones). Blank JIDs are
     * skipped; an empty JID list still builds a query (Baileys does
     * not validate users, it only notes a TODO to warn).
     */
    public static GenericQuery buildDisappearingModeGenericQuery(Collection<String> jids) {
        List<BinaryNode> users = new ArrayList<>();
        if (jids != null) {
            for (String jid : jids) {
                if (jid == null || jid.isBlank()) continue;
                users.add(new BinaryNode("user", Map.of("jid", jid), null));
            }
        }
        return new GenericQuery(
            List.of(new BinaryNode("disappearing_mode", Map.of(), null)),
            users, "interactive", "query");
    }

    /**
     * Parses a disappearing-mode generic result (see
     * {@link #buildDisappearingModeGenericQuery} and
     * {@link JaWaClient#executeUSyncQuery}) into a JID-keyed map,
     * mirroring Baileys' {@code parseUSyncQueryResult} dispatch
     * through the disappearing-mode parser: each result-list entry
     * contributes its {@code disappearing_mode} child's
     * {@code duration} / {@code t} attrs, keyed by the user node's
     * {@code jid}. Users without a {@code disappearing_mode} child,
     * or with an unparseable duration, are skipped (Baileys would
     * yield {@code NaN} for the latter, which has no Java
     * representation here). A null result yields an empty map.
     * Baileys' per-entry shape is {@code {id, disappearing_mode:
     * {duration, setAt}}}; this map flattens it to
     * {@code id -> DisappearingDuration}.
     */
    public static Map<String, DisappearingDuration> parseDisappearingModeResult(GenericResult result) {
        Map<String, DisappearingDuration> map = new LinkedHashMap<>();
        if (result == null) return map;
        for (BinaryNode user : result.userNodes()) {
            String jid = user.attr("jid");
            if (jid == null) continue;
            BinaryNode mode = user.child("disappearing_mode");
            if (mode == null) continue;
            Long duration = parseLongOrNull(mode.attr("duration"));
            if (duration == null) continue;
            Long t = parseLongOrNull(mode.attr("t"));
            long setAtMs = (t == null ? 0L : t) * 1000L;
            map.put(jid, new DisappearingDuration(duration, setAtMs));
        }
        return map;
    }

    /**
     * Caller-supplied arbitrary USync query, mirroring Baileys'
     * {@code USyncQuery} object in {@code lib/WAUSync/USyncQuery.js} and
     * {@code executeUSyncQuery} in {@code lib/Socket/socket.js}.
     * Baileys builds one {@code <user>} node per user (with a
     * {@code jid} attribute only when the user has no phone) whose
     * children are the per-protocol user elements, and one
     * {@code <query>} node whose children are the per-protocol query
     * elements. Wa-java's fixed builders ({@link #buildContactQuery},
     * {@link #buildDeviceListQuery}, {@link #buildUserInfoQuery}) cannot
     * express an arbitrary protocol set, so this model carries the
     * protocol elements verbatim: the caller owns the user-node shape
     * (including which users carry a {@code jid} attribute) exactly as
     * Baileys' {@code USyncUser} / protocol {@code getUserElement}
     * implementations own it there.
     *
     * @param queryElements per-protocol {@code <query>} children; must be non-empty
     *                      (Baileys throws "USyncQuery must have at least one protocol")
     * @param userElements per-user {@code <user>} nodes for the {@code <list>}
     * @param context Baileys {@code usync} context; blank defaults to {@code "interactive"}
     * @param mode Baileys {@code usync} mode; blank defaults to {@code "query"}
     */
    public record GenericQuery(
        List<BinaryNode> queryElements,
        List<BinaryNode> userElements,
        String context,
        String mode
    ) {
        public GenericQuery {
            queryElements = queryElements == null ? List.of() : List.copyOf(queryElements);
            userElements = userElements == null ? List.of() : List.copyOf(userElements);
            context = context == null || context.isBlank() ? "interactive" : context;
            mode = mode == null || mode.isBlank() ? "query" : mode;
        }
    }

    /**
     * Parsed arbitrary USync result, mirroring Baileys'
     * {@code parseUSyncQueryResult} in {@code lib/WAUSync/USyncQuery.js}:
     * the {@code <list>} children that carry a {@code jid} attribute
     * (Baileys skips list nodes without one). Per-protocol field
     * parsing is intentionally not done here: Baileys dispatches each
     * child tag through that protocol's parser, and wa-java has no
     * registry of arbitrary protocol parsers — callers run the
     * protocol parsers they need over the {@code userNodes} (or over
     * the raw {@code usyncNode}, which also exposes Baileys' not-yet-
     * implemented side list and error nodes).
     *
     * @param usyncNode the result's {@code <usync>} child, or null when absent
     * @param userNodes {@code <list>} children carrying a {@code jid} attribute, in order
     */
    public record GenericResult(BinaryNode usyncNode, List<BinaryNode> userNodes) {}

    /**
     * Builds an arbitrary USync IQ, mirroring Baileys'
     * {@code executeUSyncQuery} stanza in {@code lib/Socket/socket.js}:
     * {@code <iq to=s.whatsapp.net type=get xmlns=usync>} carrying
     * {@code <usync context mode sid last=true index=0>} with
     * {@code <query>} then {@code <list>} children.
     *
     * @throws IllegalArgumentException when the query has no protocol elements
     */
    public static BinaryNode buildGenericQuery(String iqId, String sid, GenericQuery query) {
        Objects.requireNonNull(query, "query");
        if (query.queryElements().isEmpty()) {
            throw new IllegalArgumentException("USyncQuery must have at least one protocol");
        }
        BinaryNode usync = new BinaryNode("usync",
            Map.of(
                "sid", sid,
                "mode", query.mode(),
                "last", "true",
                "index", "0",
                "context", query.context()
            ),
            List.of(
                new BinaryNode("query", Map.of(), query.queryElements()),
                new BinaryNode("list", Map.of(), query.userElements())
            ));
        return new BinaryNode("iq",
            Map.of(
                "xmlns", "usync",
                "type", "get",
                "to", Jid.SERVER_WHATSAPP,
                "id", iqId
            ),
            List.of(usync));
    }

    /**
     * Parses an arbitrary USync result IQ. Mirrors Baileys'
     * {@code parseUSyncQueryResult}: a non-{@code result} IQ yields
     * null (Baileys returns {@code undefined}); a {@code result} IQ
     * yields the {@code <usync>} node plus its jid-carrying
     * {@code <list>} children (empty when the {@code <usync>} or
     * {@code <list>} node is absent).
     */
    public static GenericResult parseGenericResult(BinaryNode resultIq) {
        if (resultIq == null || !"result".equals(resultIq.attr("type"))) return null;
        BinaryNode usync = resultIq.child("usync");
        if (usync == null) return new GenericResult(null, List.of());
        BinaryNode list = usync.child("list");
        if (list == null) return new GenericResult(usync, List.of());
        List<BinaryNode> users = new ArrayList<>();
        for (BinaryNode user : list.childrenList()) {
            if ("user".equals(user.tag()) && user.attr("jid") != null) users.add(user);
        }
        return new GenericResult(usync, List.copyOf(users));
    }

    private static Long parseLongOrNull(String s) {
        if (s == null) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int parseIntOr(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
