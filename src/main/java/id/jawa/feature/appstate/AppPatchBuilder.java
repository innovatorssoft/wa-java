package id.jawa.feature.appstate;

import id.jawa.proto.Wa;

import java.util.List;

/**
 * Pure builders for outbound app-state patches for contacts and labels.
 *
 * <p>Baileys source: {@code chatModificationToAppPatch} in
 * {@code lib/Utils/chat-utils.js}, reached from {@code chatModify} in
 * {@code lib/Socket/chats.js} ({@code addOrEditContact}, {@code removeContact},
 * {@code addLabel}, {@code addChatLabel}, {@code removeChatLabel},
 * {@code addMessageLabel}, {@code removeMessageLabel}). Each patch carries
 * the sync-action protobuf value, the JSON index array, the patch type,
 * the API version, and the operation (Baileys {@code OP.SET}/{@code OP.REMOVE}).
 *
 * <p>Transport gap, closed in Increment 5: Baileys sends these patches
 * with {@code appPatch}, and wa-java now has one —
 * {@code JaWaClient.appPatch} (plus the no-transport
 * {@code ChatService.sendServer*} overloads that delegate to it).
 * {@link AppPatch} remains a pure request model; sending goes through
 * that hook or a caller-supplied {@link AppPatchTransport}.
 *
 * <p>Wire note: Baileys' {@code LabelEditAction} proto fields
 * {@code color} and {@code predefinedId} are {@code int32} (see
 * {@code wa.proto}), even though wa-java's {@link id.jawa.event.LabelsEdit}
 * record carries {@code color} as a String. Builders here therefore take
 * ints, matching the wire; converting a {@code LabelsEdit} requires the
 * caller to parse its color/predefinedId as ints.
 */
public final class AppPatchBuilder {

    private AppPatchBuilder() {}

    /** Patch operation, mirroring Baileys {@code OP}. */
    public enum Operation {
        SET, REMOVE
    }

    /**
     * One outbound app-state patch, ready for an {@code appPatch} transport
     * to encrypt and send. {@code syncAction} is the
     * {@code SyncActionValue} proto (Baileys sets {@code timestamp} on it
     * at build time); callers that need a timestamp should set it on the
     * value before handing the patch to the transport, as Baileys does
     * ({@code patch.syncAction.timestamp = Date.now()}).
     */
    public record AppPatch(
        List<String> index,
        Wa.SyncActionValue syncAction,
        String type,
        int apiVersion,
        Operation operation
    ) {
        public AppPatch {
            index = index == null ? List.of() : List.copyOf(index);
            if (syncAction == null) throw new IllegalArgumentException("syncAction must not be null");
            if (type == null || type.isBlank()) throw new IllegalArgumentException("type must not be blank");
        }
    }

    /**
     * Baileys {@code addOrEditContact}: index {@code ['contact', jid]},
     * type {@code critical_unblock_low}, apiVersion 2, operation SET.
     * Mirrors Baileys {@code processContactAction} field use
     * ({@code fullName}, {@code firstName}, {@code username},
     * {@code lidJid}, {@code pnJid}); null fields are left unset.
     */
    public static AppPatch buildAddOrEditContact(String jid, String fullName, String firstName,
                                                 String username, String lidJid, String pnJid) {
        require(jid, "jid");
        Wa.SyncActionValue.ContactAction.Builder contact = Wa.SyncActionValue.ContactAction.newBuilder();
        if (fullName != null) contact.setFullName(fullName);
        if (firstName != null) contact.setFirstName(firstName);
        if (username != null) contact.setUsername(username);
        if (lidJid != null) contact.setLidJid(lidJid);
        if (pnJid != null) contact.setPnJid(pnJid);
        Wa.SyncActionValue value = Wa.SyncActionValue.newBuilder()
            .setContactAction(contact)
            .build();
        return new AppPatch(List.of("contact", jid), value, "critical_unblock_low", 2, Operation.SET);
    }

    /**
     * Baileys {@code removeContact}: index {@code ['contact', jid]}, type
     * {@code critical_unblock_low}, apiVersion 2, operation REMOVE, with an
     * empty {@code contactAction} (Baileys sends {@code contactAction: {}}).
     */
    public static AppPatch buildRemoveContact(String jid) {
        require(jid, "jid");
        Wa.SyncActionValue value = Wa.SyncActionValue.newBuilder()
            .setContactAction(Wa.SyncActionValue.ContactAction.newBuilder())
            .build();
        return new AppPatch(List.of("contact", jid), value, "critical_unblock_low", 2, Operation.REMOVE);
    }

    /**
     * Baileys {@code addLabel}: index {@code ['label_edit', labelId]}, type
     * {@code regular}, apiVersion 3, operation SET. A null
     * {@code predefinedId} leaves the field unset (Baileys passes it through
     * only when present).
     */
    public static AppPatch buildLabelEdit(String labelId, String name, int color,
                                          Integer predefinedId, boolean deleted) {
        require(labelId, "labelId");
        Wa.SyncActionValue.LabelEditAction.Builder label = Wa.SyncActionValue.LabelEditAction.newBuilder()
            .setColor(color)
            .setDeleted(deleted);
        if (name != null) label.setName(name);
        if (predefinedId != null) label.setPredefinedId(predefinedId);
        Wa.SyncActionValue value = Wa.SyncActionValue.newBuilder()
            .setLabelEditAction(label)
            .build();
        return new AppPatch(List.of("label_edit", labelId), value, "regular", 3, Operation.SET);
    }

    /** Baileys {@code addLabel} with {@code deleted: true} (label delete). */
    public static AppPatch buildLabelDelete(String labelId) {
        require(labelId, "labelId");
        Wa.SyncActionValue.LabelEditAction label = Wa.SyncActionValue.LabelEditAction.newBuilder()
            .setDeleted(true)
            .build();
        Wa.SyncActionValue value = Wa.SyncActionValue.newBuilder()
            .setLabelEditAction(label)
            .build();
        return new AppPatch(List.of("label_edit", labelId), value, "regular", 3, Operation.SET);
    }

    /**
     * Baileys {@code addChatLabel}/{@code removeChatLabel}: index
     * {@code ['label_jid', labelId, chatJid]}, type {@code regular},
     * apiVersion 3, operation SET, {@code labeled} true/false.
     */
    public static AppPatch buildChatLabel(String chatJid, String labelId, boolean labeled) {
        require(chatJid, "chatJid");
        require(labelId, "labelId");
        return labelAssociation(List.of("label_jid", labelId, chatJid), labeled);
    }

    /**
     * Baileys {@code addMessageLabel}/{@code removeMessageLabel}: index
     * {@code ['label_message', labelId, chatJid, messageId, '0', '0']},
     * type {@code regular}, apiVersion 3, operation SET, {@code labeled}
     * true/false.
     */
    public static AppPatch buildMessageLabel(String chatJid, String messageId, String labelId, boolean labeled) {
        require(chatJid, "chatJid");
        require(messageId, "messageId");
        require(labelId, "labelId");
        return labelAssociation(List.of("label_message", labelId, chatJid, messageId, "0", "0"), labeled);
    }

    /**
     * Baileys {@code addOrEditQuickReply} via {@code chatModificationToAppPatch}
     * ({@code lib/Utils/chat-utils.js:562} {@code quickReply} branch, reached
     * from {@code lib/Socket/chats.js:915}): index
     * {@code ['quick_reply', timestamp]}, type {@code regular}, apiVersion 2,
     * operation SET. The action carries {@code count: 0},
     * {@code keywords: []} (always empty in Baileys), the message and
     * shortcut (empty string when Baileys receives a falsy value), and
     * {@code deleted}. A null {@code timestampSeconds} defaults to the
     * current epoch seconds, exactly as Baileys does
     * ({@code mod.quickReply.timestamp || String(Math.floor(Date.now() / 1000))}).
     * Note Baileys sends operation SET even for a delete; deletion is
     * expressed by {@code deleted: true}, not by the operation.
     */
    public static AppPatch buildQuickReply(String shortcut, String message,
                                           String timestampSeconds, boolean deleted) {
        String timestamp = timestampSeconds != null && !timestampSeconds.isBlank()
            ? timestampSeconds
            : String.valueOf(System.currentTimeMillis() / 1000L);
        Wa.SyncActionValue.QuickReplyAction.Builder action =
            Wa.SyncActionValue.QuickReplyAction.newBuilder()
                .setCount(0)
                .setDeleted(deleted)
                // Baileys sends '' for a falsy shortcut/message, so set
                // explicitly rather than leaving the fields unset.
                .setShortcut(shortcut == null ? "" : shortcut)
                .setMessage(message == null ? "" : message);
        Wa.SyncActionValue value = Wa.SyncActionValue.newBuilder()
            .setQuickReplyAction(action)
            .build();
        return new AppPatch(List.of("quick_reply", timestamp), value, "regular", 2, Operation.SET);
    }

    /**
     * Increment 7: Baileys {@code updateProfileName}
     * ({@code lib/Socket/chats.js:285}, via {@code chatModify({pushNameSetting: name}, '')}
     * and the {@code pushNameSetting} branch of
     * {@code chatModificationToAppPatch} in
     * {@code lib/Utils/chat-utils.js:549}): index
     * {@code ['setting_pushName']}, type {@code critical_block},
     * apiVersion 1, operation SET, carrying
     * {@code pushNameSetting {name}}. The name is passed through
     * verbatim (Baileys sends whatever string it receives); only a
     * null name is rejected. This is the app-state path for changing
     * the profile name — distinct from the pre-existing
     * {@code JaWaClient.updatePushName}, which only updates the local
     * credential and broadcasts a {@code <presence name>} stanza and
     * never sends an app-state patch (see that method's Javadoc for
     * the relationship between the two paths).
     */
    public static AppPatch buildPushNameSetting(String name) {
        if (name == null) throw new IllegalArgumentException("name must not be null");
        Wa.SyncActionValue.PushNameSetting setting = Wa.SyncActionValue.PushNameSetting.newBuilder()
            .setName(name)
            .build();
        Wa.SyncActionValue value = Wa.SyncActionValue.newBuilder()
            .setPushNameSetting(setting)
            .build();
        return new AppPatch(List.of("setting_pushName"), value, "critical_block", 1, Operation.SET);
    }

    /**
     * Increment 7: Baileys {@code updateDisableLinkPreviewsPrivacy}
     * ({@code lib/Socket/chats.js:828}, via
     * {@code chatModify({disableLinkPreviews: {isPreviewsDisabled}}, '')}
     * and the {@code disableLinkPreviews} branch of
     * {@code chatModificationToAppPatch} in
     * {@code lib/Utils/chat-utils.js:511}): index
     * {@code ['setting_disableLinkPreviews']}, type {@code regular},
     * apiVersion 8, operation SET, carrying
     * {@code privacySettingDisableLinkPreviewsAction {isPreviewsDisabled}}.
     * Despite the name, Baileys does <em>not</em> send this as a
     * privacy IQ — it is an app-state patch, which is why it lives
     * here rather than in the privacy-IQ builders.
     */
    public static AppPatch buildDisableLinkPreviews(boolean isPreviewsDisabled) {
        Wa.SyncActionValue.PrivacySettingDisableLinkPreviewsAction action =
            Wa.SyncActionValue.PrivacySettingDisableLinkPreviewsAction.newBuilder()
                .setIsPreviewsDisabled(isPreviewsDisabled)
                .build();
        Wa.SyncActionValue value = Wa.SyncActionValue.newBuilder()
            .setPrivacySettingDisableLinkPreviewsAction(action)
            .build();
        return new AppPatch(List.of("setting_disableLinkPreviews"), value, "regular", 8, Operation.SET);
    }

    /**
     * Baileys {@code addOrEditQuickReply} with {@code deleted: false}:
     * creates or edits a quick reply. A null timestamp defaults to the
     * current epoch seconds (see {@link #buildQuickReply}).
     */
    public static AppPatch buildAddOrEditQuickReply(String shortcut, String message, String timestampSeconds) {
        return buildQuickReply(shortcut, message, timestampSeconds, false);
    }

    /**
     * Baileys {@code removeQuickReply} ({@code lib/Socket/chats.js:923}):
     * {@code chatModify({quickReply: {timestamp, deleted: true}})}.
     * The timestamp is required here: Baileys passes it through as the
     * patch index, and a missing timestamp would address a fresh
     * (now-seconds) index instead of the reply being removed.
     */
    public static AppPatch buildRemoveQuickReply(String timestampSeconds) {
        require(timestampSeconds, "timestampSeconds");
        return buildQuickReply(null, null, timestampSeconds, true);
    }

    private static AppPatch labelAssociation(List<String> index, boolean labeled) {
        Wa.SyncActionValue value = Wa.SyncActionValue.newBuilder()
            .setLabelAssociationAction(Wa.SyncActionValue.LabelAssociationAction.newBuilder()
                .setLabeled(labeled))
            .build();
        return new AppPatch(index, value, "regular", 3, Operation.SET);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
