package id.jawa.feature.media;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record MediaConn(
    String auth,
    int ttlSeconds,
    int authTtlSeconds,
    int maxBuckets,
    List<String> hosts,
    Instant fetchedAt
) {

    public Instant expiry() {
        return fetchedAt.plusSeconds(ttlSeconds);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiry());
    }

    public static BinaryNode buildQuery(String iqId) {
        return new BinaryNode("iq",
            Map.of(
                "id",    iqId,
                "type",  "set",
                "xmlns", "w:m",
                "to",    id.jawa.domain.model.Jid.SERVER_WHATSAPP
            ),
            List.of(BinaryNode.of("media_conn")));
    }

    public static MediaConn parseResponse(BinaryNode iqResult) {
        BinaryNode mc = iqResult.child("media_conn");
        if (mc == null) return null;
        String auth = mc.attr("auth");
        if (auth == null) return null;
        int ttl = parseInt(mc.attr("ttl"), 60);
        int authTtl = parseInt(mc.attr("auth_ttl"), 30);
        int maxBuckets = parseInt(mc.attr("max_buckets"), 12);

        List<String> hosts = new ArrayList<>();
        for (BinaryNode child : mc.childrenList()) {
            if (!"host".equals(child.tag())) continue;
            String hostname = child.attr("hostname");
            if (hostname != null) hosts.add(hostname);
        }
        return new MediaConn(auth, ttl, authTtl, maxBuckets, hosts, Instant.now());
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return fallback; }
    }
}
