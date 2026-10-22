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
                JaWaClient.LID_TO_PN_MAP.put(userJid, pnJid);
            } else if (lidVal != null && lidVal.endsWith("@lid") && userJid != null && userJid.endsWith("@s.whatsapp.net")) {
                JaWaClient.LID_TO_PN_MAP.put(lidVal, userJid);
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

    private static int parseIntOr(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
