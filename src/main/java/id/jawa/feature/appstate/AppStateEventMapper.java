package id.jawa.feature.appstate;

import id.jawa.event.BaileysEventType;
import id.jawa.event.ChatsLock;
import id.jawa.event.LabelsAssociation;
import id.jawa.event.LabelsEdit;
import id.jawa.event.MessagingHistorySet;
import id.jawa.event.MessagingHistoryStatus;
import id.jawa.event.SettingsUpdate;
import id.jawa.feature.messaging.MessageReceiver;
import id.jawa.proto.Wa;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure mapper from decoded app-state sync actions and history-sync payloads
 * to typed Baileys event payloads.
 *
 * <p>Baileys sources: {@code processSyncAction} in
 * {@code lib/Utils/chat-utils.js} (labels, settings, chat lock),
 * {@code processHistoryMessage} in {@code lib/Utils/history.js} and the
 * {@code messaging-history.set} emit in {@code lib/Utils/process-message.js},
 * and the {@code messaging-history.status} emits in
 * {@code lib/Socket/chats.js}.
 *
 * <p><b>Wiring (Increment 3).</b> This mapper itself performs no dispatch:
 * it only produces the payloads. {@code JaWaClient.requestAppStateSync}
 * feeds it decoded {@link AppStateProcessor.Mutation}s and dispatches the
 * returned {@code labels.edit} / {@code labels.association} /
 * {@code settings.update} / {@code chats.lock} events on the
 * {@code WaEventBus}. The history helpers ({@code mapHistorySet},
 * {@code historyStatusForNotification}) are <b>not</b> wired by the client
 * yet, because no history-sync notification path exists in this client.
 * Nothing here subscribes to the bus or touches the store.
 *
 * <p>Scope and deliberate omissions:
 * <ul>
 *   <li>Only the events named in the task are mapped: {@code labels.edit},
 *       {@code labels.association}, {@code settings.update},
 *       {@code chats.lock}, {@code messaging-history.set} and the explicit
 *       {@code messaging-history.status} completions. Any other sync action
 *       maps to an empty list — it is unmapped, not an error — exactly as
 *       Baileys' {@code processSyncAction} falls through to its
 *       "unprocessable update" debug log for actions it does not handle
 *       here.</li>
 *   <li>Index parsing: Baileys decodes the index as a JSON array string
 *       ({@code JSON.parse(Buffer.from(syncAction.index).toString())} in
 *       {@code lib/Utils/chat-utils.js}). This mapper parses that same
 *       string form with a small string-array parser; a malformed index
 *       yields an empty list, never an exception.</li>
 *   <li>{@code settings.update} values: Baileys passes most setting actions
 *       through as objects. Those values are carried here as the proto
 *       action messages themselves (the {@code Object} value of
 *       {@link SettingsUpdate} is unchecked by design); only
 *       {@code locale} is a plain string, as in Baileys.</li>
 *   <li>{@code labels.edit} conversion: the wire {@code color} and
 *       {@code predefinedId} are {@code int32} (see {@code wa.proto}),
 *       while {@link LabelsEdit} carries strings. The mapper stringifies
 *       them, and drops {@code predefinedId} when it is unset or zero —
 *       Baileys drops it when falsy
 *       ({@code predefinedId ? String(predefinedId) : undefined}).</li>
 *   <li>{@code messaging-history.status} is stateful in Baileys (completion
 *       flags plus a 120s "paused" timeout in {@code lib/Socket/chats.js}).
 *       A pure mapper cannot reproduce that state machine, so
 *       {@link #historyStatusForNotification(Wa.HistorySyncType, Integer)}
 *       maps only the two explicit completions (initial bootstrap, and
 *       RECENT at progress 100, both {@code explicit: true}); the
 *       {@code paused} case and deduplication are left to the future
 *       client wiring and are documented as not mapped.</li>
 *   <li>{@code messaging-history.set}: {@code isLatest} is null for
 *       ON_DEMAND syncs (Baileys passes {@code undefined} there) and is
 *       otherwise the caller-supplied value, because Baileys computes it
 *       outside the HistorySync proto. Full {@code Chat[]} /
 *       {@code Contact[]} objects are not modelled (see
 *       {@link MessagingHistorySet}); chat and contact ids come from the
 *       conversation ids, and messages are rebuilt as
 *       {@link MessageReceiver.Decoded} records from the already-decrypted
 *       history messages.</li>
 * </ul>
 */
public final class AppStateEventMapper {

    private AppStateEventMapper() {}

    /** One mapped event: its Baileys event type and its typed payload. */
    public record MappedEvent(BaileysEventType type, Object payload) {}

    /**
     * Maps one decoded sync action to its event payloads (zero or one for
     * the actions covered here).
     *
     * @param action decoded {@code SyncActionData}; null yields an empty list
     * @return list of mapped events, never null
     */
    public static List<MappedEvent> map(Wa.SyncActionData action) {
        List<MappedEvent> out = new ArrayList<>();
        if (action == null || !action.hasValue()) return out;
        Wa.SyncActionValue value = action.getValue();
        List<String> index = parseIndex(action);

        if (value.hasLabelEditAction()) {
            if (index.size() < 2) return out;
            Wa.SyncActionValue.LabelEditAction edit = value.getLabelEditAction();
            String predefinedId = edit.hasPredefinedId() && edit.getPredefinedId() != 0
                ? String.valueOf(edit.getPredefinedId()) : null;
            String color = edit.hasColor() ? String.valueOf(edit.getColor()) : null;
            out.add(new MappedEvent(BaileysEventType.LABELS_EDIT,
                new LabelsEdit(index.get(1),
                    edit.hasName() ? edit.getName() : null,
                    color,
                    edit.hasDeleted() && edit.getDeleted(),
                    predefinedId)));
            return out;
        }
        if (value.hasLabelAssociationAction()) {
            if (index.size() < 3) return out;
            String associationType = index.get(0);
            boolean isMessage = "label_message".equals(associationType);
            if (!"label_jid".equals(associationType) && !isMessage) return out;
            String messageId = null;
            if (isMessage) {
                if (index.size() < 4) return out;
                messageId = index.get(3);
            }
            out.add(new MappedEvent(BaileysEventType.LABELS_ASSOCIATION,
                new LabelsAssociation(
                    value.getLabelAssociationAction().getLabeled() ? "add" : "remove",
                    associationType,
                    index.get(2),
                    index.get(1),
                    messageId)));
            return out;
        }
        if (value.hasLocaleSetting() && value.getLocaleSetting().hasLocale()
            && !value.getLocaleSetting().getLocale().isEmpty()) {
            out.add(new MappedEvent(BaileysEventType.SETTINGS_UPDATE,
                new SettingsUpdate("locale", value.getLocaleSetting().getLocale())));
            return out;
        }
        if (value.hasTimeFormatAction()) {
            out.add(new MappedEvent(BaileysEventType.SETTINGS_UPDATE,
                new SettingsUpdate("timeFormat", value.getTimeFormatAction())));
            return out;
        }
        if (value.hasPrivacySettingRelayAllCalls()) {
            out.add(new MappedEvent(BaileysEventType.SETTINGS_UPDATE,
                new SettingsUpdate("privacySettingRelayAllCalls", value.getPrivacySettingRelayAllCalls())));
            return out;
        }
        if (value.hasStatusPrivacy()) {
            out.add(new MappedEvent(BaileysEventType.SETTINGS_UPDATE,
                new SettingsUpdate("statusPrivacy", value.getStatusPrivacy())));
            return out;
        }
        if (value.hasLockChatAction()) {
            if (index.size() < 2) return out;
            out.add(new MappedEvent(BaileysEventType.CHATS_LOCK,
                new ChatsLock(index.get(1), value.getLockChatAction().getLocked())));
            return out;
        }
        if (value.hasPrivacySettingDisableLinkPreviewsAction()) {
            out.add(new MappedEvent(BaileysEventType.SETTINGS_UPDATE,
                new SettingsUpdate("disableLinkPreviews", value.getPrivacySettingDisableLinkPreviewsAction())));
            return out;
        }
        if (value.hasNotificationActivitySettingAction()
            && value.getNotificationActivitySettingAction().hasNotificationActivitySetting()) {
            out.add(new MappedEvent(BaileysEventType.SETTINGS_UPDATE,
                new SettingsUpdate("notificationActivitySetting",
                    value.getNotificationActivitySettingAction())));
            return out;
        }
        if (value.hasPrivacySettingChannelsPersonalisedRecommendationAction()) {
            out.add(new MappedEvent(BaileysEventType.SETTINGS_UPDATE,
                new SettingsUpdate("channelsPersonalisedRecommendation",
                    value.getPrivacySettingChannelsPersonalisedRecommendationAction())));
            return out;
        }
        return out;
    }

    /** Convenience overload mapping a decoded {@link AppStateProcessor.Mutation}. */
    public static List<MappedEvent> mapMutation(AppStateProcessor.Mutation mutation) {
        if (mutation == null) return List.of();
        return map(mutation.action());
    }

    /**
     * Maps a decoded {@code HistorySync} proto to a
     * {@code messaging-history.set} payload, following Baileys'
     * {@code processHistoryMessage} (conversations and PUSH_NAME) and the
     * {@code messaging-history.set} emit in {@code process-message.js}.
     *
     * @param history decoded HistorySync proto; null yields a payload with empty lists
     * @param isLatest caller-computed latest-chunk flag; ignored (null in
     *                 the payload) for ON_DEMAND syncs, per Baileys
     * @param peerDataRequestSessionId session id from the history-sync
     *                                 notification, or null
     * @return the mapped event
     */
    public static MappedEvent mapHistorySet(Wa.HistorySync history, boolean isLatest,
                                            String peerDataRequestSessionId) {
        if (history == null) {
            return new MappedEvent(BaileysEventType.MESSAGING_HISTORY_SET,
                new MessagingHistorySet(List.of(), List.of(), List.of(),
                    null, null, null, null, peerDataRequestSessionId));
        }
        String syncType = history.hasSyncType() ? history.getSyncType().name() : null;
        Long progress = history.hasProgress() ? (long) history.getProgress() : null;
        Integer chunkOrder = history.hasChunkOrder() ? history.getChunkOrder() : null;
        Boolean effectiveIsLatest = Wa.HistorySync.HistorySyncType.ON_DEMAND.equals(history.getSyncType())
            ? null : isLatest;
        List<String> chatIds = new ArrayList<>();
        List<String> contactIds = new ArrayList<>();
        List<MessageReceiver.Decoded> messages = new ArrayList<>();
        if (Wa.HistorySync.HistorySyncType.PUSH_NAME.equals(history.getSyncType())) {
            for (Wa.Pushname pushname : history.getPushnamesList()) {
                if (pushname.hasId()) contactIds.add(pushname.getId());
            }
        } else {
            for (Wa.Conversation conversation : history.getConversationsList()) {
                if (!conversation.hasId()) continue;
                chatIds.add(conversation.getId());
                contactIds.add(conversation.getId());
                for (Wa.HistorySyncMsg item : conversation.getMessagesList()) {
                    if (!item.hasMessage()) continue;
                    Wa.WebMessageInfo info = item.getMessage();
                    if (!info.hasKey()) continue;
                    Wa.MessageKey key = info.getKey();
                    String participant = key.hasParticipant() ? key.getParticipant() : null;
                    String remoteJid = key.hasRemoteJid() ? key.getRemoteJid() : null;
                    String senderJid = participant != null ? participant : remoteJid;
                    if (senderJid == null) continue;
                    String groupJid = participant != null && remoteJid != null && !remoteJid.equals(participant)
                        ? remoteJid : null;
                    Wa.Message message = info.hasMessage() ? info.getMessage() : null;
                    String text = null;
                    if (message != null) {
                        if (message.hasConversation()) text = message.getConversation();
                        else if (message.hasExtendedTextMessage()
                            && message.getExtendedTextMessage().hasText()) {
                            text = message.getExtendedTextMessage().getText();
                        }
                    }
                    messages.add(new MessageReceiver.Decoded(senderJid, groupJid,
                        key.hasId() ? key.getId() : null, null, message, text, null,
                        info.hasMessageTimestamp() ? info.getMessageTimestamp() : 0L));
                }
            }
        }
        return new MappedEvent(BaileysEventType.MESSAGING_HISTORY_SET,
            new MessagingHistorySet(chatIds, messages, contactIds, syncType,
                progress, effectiveIsLatest, chunkOrder, peerDataRequestSessionId));
    }

    /**
     * Maps a history-sync notification to its explicit
     * {@code messaging-history.status} completion, if any. Only the two
     * explicit cases from {@code lib/Socket/chats.js} are mapped:
     * INITIAL_BOOTSTRAP, and RECENT with progress exactly 100 — both
     * {@code status: complete, explicit: true}. Every other input returns
     * null: the {@code paused} emission is timer-driven and stateful, and
     * Baileys additionally deduplicates with completion flags, neither of
     * which a pure function can reproduce.
     */
    public static MessagingHistoryStatus historyStatusForNotification(Wa.HistorySync.HistorySyncType syncType,
                                                                      Integer progress) {
        if (Wa.HistorySync.HistorySyncType.INITIAL_BOOTSTRAP.equals(syncType)) {
            return new MessagingHistoryStatus("INITIAL_BOOTSTRAP", "complete", true);
        }
        if (Wa.HistorySync.HistorySyncType.RECENT.equals(syncType)
            && progress != null && progress == 100) {
            return new MessagingHistoryStatus("RECENT", "complete", true);
        }
        return null;
    }

    /**
     * Parses the sync-action index bytes as a JSON string array, e.g.
     * {@code ["label_jid","12","123@s.whatsapp.net"]}. Returns an empty
     * list for null/blank/malformed input, never throwing.
     */
    static List<String> parseIndex(Wa.SyncActionData action) {
        List<String> out = new ArrayList<>();
        if (action == null || !action.hasIndex()) return out;
        String raw = new String(action.getIndex().toByteArray(), StandardCharsets.UTF_8).trim();
        if (!raw.startsWith("[") || !raw.endsWith("]")) return out;
        String inner = raw.substring(1, raw.length() - 1).trim();
        if (inner.isEmpty()) return out;
        int i = 0;
        while (true) {
            while (i < inner.length() && inner.charAt(i) == ' ') i++;
            if (i >= inner.length()) break;
            if (inner.charAt(i) != '"') return List.of();
            StringBuilder sb = new StringBuilder();
            i++;
            boolean closed = false;
            while (i < inner.length()) {
                char c = inner.charAt(i);
                if (c == '\\' && i + 1 < inner.length()) {
                    sb.append(inner.charAt(i + 1));
                    i += 2;
                } else if (c == '"') {
                    closed = true;
                    i++;
                    break;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            if (!closed) return List.of();
            out.add(sb.toString());
            while (i < inner.length() && inner.charAt(i) == ' ') i++;
            if (i >= inner.length()) break;
            if (inner.charAt(i) != ',') return List.of();
            i++;
            while (i < inner.length() && inner.charAt(i) == ' ') i++;
            if (i >= inner.length()) return List.of();
        }
        return out;
    }
}
