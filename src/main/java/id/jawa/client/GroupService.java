package id.jawa.client;

import id.jawa.feature.messaging.GroupAction;
import id.jawa.feature.messaging.GroupListQuery;
import id.jawa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class GroupService {

    private final JaWaClient client;

    GroupService(JaWaClient client) {
        this.client = client;
    }

    public CompletableFuture<String> createGroup(String subject, List<String> participants) {
        return client.createGroup(subject, participants);
    }

    public CompletableFuture<Void> leaveGroup(String groupJid) {
        return client.leaveGroup(groupJid);
    }

    public CompletableFuture<Void> updateGroupParticipants(String groupJid,
                                                           GroupAction.ParticipantChange action,
                                                           List<String> participantBareJids) {
        return client.updateGroupParticipants(groupJid, action, participantBareJids);
    }

    public CompletableFuture<Void> setGroupSubject(String groupJid, String name) {
        return client.setGroupSubject(groupJid, name);
    }

    public CompletableFuture<Void> setGroupDescription(String groupJid, String body, String previousId) {
        return client.setGroupDescription(groupJid, body, previousId);
    }

    public CompletableFuture<Void> setGroupDescription(String groupJid, String body) {
        return client.setGroupDescription(groupJid, body, null);
    }

    public CompletableFuture<List<GroupListQuery.GroupInfo>> queryJoinedGroups() {
        return client.queryJoinedGroups();
    }

    public CompletableFuture<List<GroupListQuery.GroupInfo>> queryJoinedGroups(boolean forceRefresh) {
        return client.queryJoinedGroups(forceRefresh);
    }

    public CompletableFuture<BinaryNode> updateGroupPicture(String groupJid, byte[] imageBytes) {
        return client.updateGroupPicture(groupJid, imageBytes);
    }

    public CompletableFuture<BinaryNode> removeGroupPicture(String groupJid) {
        return client.removeGroupPicture(groupJid);
    }

    public CompletableFuture<Void> setDisappearingTimer(String groupJid, int timerSeconds) {
        return client.setDisappearingTimer(groupJid, timerSeconds);
    }
}
