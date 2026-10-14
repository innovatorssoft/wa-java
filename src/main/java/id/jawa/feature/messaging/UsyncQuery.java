package id.jawa.feature.messaging;

import id.jawa.protocol.connection.*;
import id.jawa.protocol.codec.*;
import id.jawa.protocol.crypto.*;
import id.jawa.domain.model.*;
import id.jawa.domain.store.*;
import id.jawa.feature.pairing.*;
import id.jawa.feature.messaging.*;
import id.jawa.feature.media.*;
import id.jawa.feature.appstate.*;
import id.jawa.feature.signal.*;

import id.jawa.protocol.codec.BinaryNode;
import id.jawa.domain.model.Jid;

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
                            id.jawa.client.JaWaClient.LID_TO_PN_MAP.put(jidBare, valBare);
                        } else if (jidBare.endsWith("@s.whatsapp.net") && valBare.endsWith("@lid")) {
                            id.jawa.client.JaWaClient.LID_TO_PN_MAP.put(valBare, jidBare);
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
            String jid = phone.contains("@") ? phone : phone + "@" + Jid.SERVER_WHATSAPP;
            userNodes.add(new BinaryNode("user", Map.of("jid", jid), null));
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
                    new BinaryNode("contact", Map.of(), null)
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
            String jid = user.attr("jid");
            if (jid == null) continue;
            BinaryNode contactNode = user.child("contact");
            boolean isIn = contactNode != null && "in".equals(contactNode.attr("type"));
            out.put(jid, new ContactStatus(jid, isIn));
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
                statusText = new String(status.bytesContent(), java.nio.charset.StandardCharsets.UTF_8);
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
