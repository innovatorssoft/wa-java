package id.ja_wa.feature.messaging;


import id.ja_wa.protocol.codec.BinaryNode;
import id.ja_wa.proto.Wa;
import id.ja_wa.protocol.crypto.Bytes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BizNode {

    private BizNode() {}

    public static BinaryNode buildIfNeeded(Wa.Message message) {
        if (message == null) return null;
        boolean isButtons     = message.hasButtonsMessage();
        boolean isList        = message.hasListMessage();
        boolean isInteractive = message.hasInteractiveMessage();
        boolean isTemplate    = message.hasTemplateMessage();
        if (!isButtons && !isList && !isInteractive && !isTemplate) return null;

        Map<String, String> bizAttrs = new LinkedHashMap<>();
        bizAttrs.put("actual_actors",   "2");
        bizAttrs.put("host_storage",    "2");
        bizAttrs.put("privacy_mode_ts", Long.toString(System.currentTimeMillis() / 1_000));

        List<BinaryNode> children = new ArrayList<>();
        if (isList) {
            children.add(new BinaryNode("list",
                Map.of("v", "2", "type", "product_list"), null));
        } else {
            children.add(new BinaryNode("interactive",
                Map.of("type", "native_flow", "v", "1"),
                List.of(new BinaryNode("native_flow",
                    Map.of("v", "9", "name", "mixed"), null))));
        }
        children.add(qualityControl());
        return new BinaryNode("biz", bizAttrs, children);
    }

    private static BinaryNode qualityControl() {
        String decisionId = Bytes.toHex(Bytes.random(20));
        BinaryNode decisionSource = new BinaryNode("decision_source",
            Map.of("value", "df"), null);
        return new BinaryNode("quality_control",
            Map.of(
                "decision_id",  decisionId,
                "source_type",  "third_party"
            ),
            List.of(decisionSource));
    }
}
