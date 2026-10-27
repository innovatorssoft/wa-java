package id.jawa.event;

import id.jawa.feature.messaging.GroupListQuery;
import id.jawa.protocol.codec.BinaryNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses Baileys {@code w:gp2} group notifications into Baileys events.
 * <p>
 * Source: Baileys {@code handleGroupNotification} in
 * {@code lib/Socket/messages-recv.js}. Baileys itself turns most of these
 * children into message stubs and then into events in
 * {@code process-message.js}; this parser emits the resulting events
 * directly, which is equivalent for consumers of the event bus (the stub
 * messages themselves are not reproduced — wa-java has no stub-message
 * layer). Differences from Baileys, all documented:
 * <ul>
 *   <li>{@code leave} is emitted as action {@code remove}: Baileys' emitted
 *       participant action set is {@code add | remove | promote | demote |
 *       modify}, and a self-remove is only re-typed to "leave" at stub
 *       level, not in the emitted event.</li>
 *   <li>{@code ephemeral}/{@code not_ephemeral} produce no event here: the
 *       client handles the ephemeral timer itself, as Baileys does via the
 *       protocol message path.</li>
 *   <li>Group-change children that Baileys reports as partial group
 *       metadata (subject, description, announce, restrict, invite code,
 *       member-add mode, join-approval mode) are emitted as
 *       {@code groups.update} with exactly those fields; other children
 *       produce no event.</li>
 * </ul>
 * <p>
 * Only the first child of the notification is parsed, matching Baileys'
 * {@code const [child] = getAllBinaryNodeChildren(node)}.
 */
public final class GroupNotificationParser {

    private GroupNotificationParser() {}

    /**
     * Parses a {@code w:gp2} notification stanza.
     *
     * @param node notification node; null or a node without children yields
     *             an empty list
     * @return events to dispatch, in Baileys' emission order
     */
    public static List<EventDispatch> parse(BinaryNode node) {
        List<EventDispatch> out = new ArrayList<>();
        if (node == null) return out;
        List<BinaryNode> children = node.childrenList();
        if (children.isEmpty()) return out;
        BinaryNode child = children.get(0);
        String from = node.attr("from");
        if (from == null) return out;
        String author = node.attr("participant");
        String authorPn = node.attr("participant_pn");
        String authorUsername = node.attr("participant_username");

        switch (child.tag()) {
            case "create" -> {
                BinaryNode groupNode = child.child("group");
                if (groupNode == null) break;
                GroupListQuery.GroupInfo parsed;
                try {
                    parsed = GroupListQuery.parseSingleGroup(groupNode);
                } catch (Throwable t) {
                    break;
                }
                if (parsed == null) break;
                out.add(new EventDispatch(BaileysEventType.CHATS_UPSERT,
                    new ChatsUpsert(List.of(parsed.jid()))));
                out.add(new EventDispatch(BaileysEventType.GROUPS_UPSERT,
                    new GroupsUpsert(List.of(new GroupsUpsert.GroupInfo(
                        parsed.jid(), parsed.subject(), author, authorPn, authorUsername,
                        parsed.participantJids())))));
            }
            case "add", "remove", "promote", "demote", "leave", "modify" -> {
                List<String> participants = new ArrayList<>();
                for (BinaryNode p : child.children("participant")) {
                    String jid = p.attr("jid");
                    if (jid != null) participants.add(jid);
                }
                if (participants.isEmpty()) break;
                String action = "leave".equals(child.tag()) ? "remove" : child.tag();
                out.add(new EventDispatch(BaileysEventType.GROUP_PARTICIPANTS_UPDATE,
                    new GroupParticipantsUpdate(from, author, authorPn, authorUsername,
                        participants, action)));
            }
            case "created_membership_requests", "revoked_membership_requests" -> {
                BinaryNode participantNode = child.child("participant");
                String participant = participantNode != null ? participantNode.attr("jid") : null;
                if (participant == null) participant = author;
                String participantPn = participantNode != null ? participantNode.attr("phone_number") : null;
                if (participantPn == null) participantPn = authorPn;
                String action;
                String method = child.attr("request_method");
                if ("created_membership_requests".equals(child.tag())) {
                    action = "created";
                } else {
                    action = participant != null && participant.equals(author) ? "revoked" : "rejected";
                }
                out.add(new EventDispatch(BaileysEventType.GROUP_JOIN_REQUEST,
                    new GroupJoinRequest(from, author, authorPn, authorUsername,
                        participant, participantPn, action, method)));
            }
            case "subject" -> {
                String subject = child.attr("subject");
                if (subject == null) break;
                out.add(new EventDispatch(BaileysEventType.GROUPS_UPDATE,
                    new GroupsUpdate(List.of(new GroupsUpdate.GroupUpdate(
                        from, subject, author, authorPn, authorUsername,
                        null, null, null, null, null, null)))));
            }
            case "description" -> {
                BinaryNode body = child.child("body");
                String description = body == null ? null : body.textContent();
                out.add(new EventDispatch(BaileysEventType.GROUPS_UPDATE,
                    new GroupsUpdate(List.of(new GroupsUpdate.GroupUpdate(
                        from, null, author, authorPn, authorUsername,
                        description, null, null, null, null, null)))));
            }
            case "announcement", "not_announcement" -> out.add(new EventDispatch(
                BaileysEventType.GROUPS_UPDATE,
                new GroupsUpdate(List.of(new GroupsUpdate.GroupUpdate(
                    from, null, author, authorPn, authorUsername,
                    null, "announcement".equals(child.tag()), null, null, null, null)))));
            case "locked", "unlocked" -> out.add(new EventDispatch(
                BaileysEventType.GROUPS_UPDATE,
                new GroupsUpdate(List.of(new GroupsUpdate.GroupUpdate(
                    from, null, author, authorPn, authorUsername,
                    null, null, "locked".equals(child.tag()), null, null, null)))));
            case "invite" -> {
                String code = child.attr("code");
                if (code == null) break;
                out.add(new EventDispatch(BaileysEventType.GROUPS_UPDATE,
                    new GroupsUpdate(List.of(new GroupsUpdate.GroupUpdate(
                        from, null, author, authorPn, authorUsername,
                        null, null, null, code, null, null)))));
            }
            case "member_add_mode" -> {
                String mode = child.textContent();
                if (mode == null) break;
                out.add(new EventDispatch(BaileysEventType.GROUPS_UPDATE,
                    new GroupsUpdate(List.of(new GroupsUpdate.GroupUpdate(
                        from, null, author, authorPn, authorUsername,
                        null, null, null, null, "all_member_add".equals(mode), null)))));
            }
            case "membership_approval_mode" -> {
                BinaryNode groupJoin = child.child("group_join");
                if (groupJoin == null) break;
                out.add(new EventDispatch(BaileysEventType.GROUPS_UPDATE,
                    new GroupsUpdate(List.of(new GroupsUpdate.GroupUpdate(
                        from, null, author, authorPn, authorUsername,
                        null, null, null, null, null, "on".equals(groupJoin.attr("state")))))));
            }
            default -> {
                // ephemeral / not_ephemeral and unknown children: no event.
            }
        }
        return out;
    }
}
