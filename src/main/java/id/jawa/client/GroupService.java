// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.client;

import id.jawa.feature.messaging.GroupAction;
import id.jawa.feature.messaging.GroupListQuery;
import id.jawa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service facade handling group chat operations in JaWa (creating groups, updating
 * participants, modifying subjects/descriptions, listing groups, and managing group profile pictures).
 */
public final class GroupService {

    private final JaWaClient client;

    GroupService(JaWaClient client) {
        this.client = client;
    }

    /** Create a new WhatsApp group chat with the given subject and initial participants. */
    public CompletableFuture<String> createGroup(String subject, List<String> participants) {
        return client.createGroup(subject, participants);
    }

    /** Leave the specified group chat. */
    public CompletableFuture<Void> leaveGroup(String groupJid) {
        return client.leaveGroup(groupJid);
    }

    /** Add, remove, promote, or demote participants in a group chat. */
    public CompletableFuture<Void> updateGroupParticipants(String groupJid,
                                                           GroupAction.ParticipantChange action,
                                                           List<String> participantBareJids) {
        return client.updateGroupParticipants(groupJid, action, participantBareJids);
    }

    /** Set or change the group subject/name. */
    public CompletableFuture<Void> setGroupSubject(String groupJid, String name) {
        return client.setGroupSubject(groupJid, name);
    }

    /** Set or clear the group description. */
    public CompletableFuture<Void> setGroupDescription(String groupJid, String body, String previousId) {
        return client.setGroupDescription(groupJid, body, previousId);
    }

    /** Set or clear the group description (convenience overload when no previousId is tracked). */
    public CompletableFuture<Void> setGroupDescription(String groupJid, String body) {
        return client.setGroupDescription(groupJid, body, null);
    }

    /** Query all groups that the current account participates in. */
    public CompletableFuture<List<GroupListQuery.GroupInfo>> queryJoinedGroups() {
        return client.queryJoinedGroups();
    }

    /** Update group display picture with JPEG image bytes. */
    public CompletableFuture<BinaryNode> updateGroupPicture(String groupJid, byte[] imageBytes) {
        return client.updateGroupPicture(groupJid, imageBytes);
    }

    /** Remove group display picture. */
    public CompletableFuture<BinaryNode> removeGroupPicture(String groupJid) {
        return client.removeGroupPicture(groupJid);
    }
}
