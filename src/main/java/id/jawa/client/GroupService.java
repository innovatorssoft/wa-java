package id.jawa.client;

import id.jawa.domain.model.CommunitySubGroup;
import id.jawa.domain.model.GroupJoinRequest;
import id.jawa.feature.messaging.GroupAction;
import id.jawa.feature.messaging.GroupListQuery;
import id.jawa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service managing WhatsApp group creation, administration, membership, permissions, and communities.
 */
public final class GroupService {

    private final JaWaClient client;

    GroupService(JaWaClient client) {
        this.client = client;
    }

    /**
     * Creates a new WhatsApp group chat with initial participants.
     *
     * @param subject Group title or name
     * @param participants List of participant phone number or LID JIDs
     * @return CompletableFuture resolving to the created group JID (e.g. 12345@g.us)
     */
    public CompletableFuture<String> createGroup(String subject, List<String> participants) {
        return client.createGroup(subject, participants);
    }

    /**
     * Leaves a group chat.
     *
     * @param groupJid Group JID
     * @return CompletableFuture completing when left
     */
    public CompletableFuture<Void> leaveGroup(String groupJid) {
        return client.leaveGroup(groupJid);
    }

    /**
     * Modifies group membership (adding, removing, promoting, demoting participants).
     *
     * @param groupJid Group JID
     * @param action Change type (ADD, REMOVE, PROMOTE, DEMOTE)
     * @param participantBareJids Target participant JIDs
     * @return CompletableFuture completing when updated
     */
    public CompletableFuture<Void> updateGroupParticipants(String groupJid,
                                                           GroupAction.ParticipantChange action,
                                                           List<String> participantBareJids) {
        return client.updateGroupParticipants(groupJid, action, participantBareJids);
    }

    /**
     * Sets the group topic subject.
     *
     * @param groupJid Group JID
     * @param name New subject name
     * @return CompletableFuture completing when saved
     */
    public CompletableFuture<Void> setGroupSubject(String groupJid, String name) {
        return client.setGroupSubject(groupJid, name);
    }

    /**
     * Updates or deletes the group description.
     *
     * @param groupJid Group JID
     * @param body New description text, or null to clear
     * @param previousId Previous description revision ID, or null
     * @return CompletableFuture completing when saved
     */
    public CompletableFuture<Void> setGroupDescription(String groupJid, String body, String previousId) {
        return client.setGroupDescription(groupJid, body, previousId);
    }

    /**
     * Updates or deletes the group description without specifying previous ID.
     *
     * @param groupJid Group JID
     * @param body New description text, or null to clear
     * @return CompletableFuture completing when saved
     */
    public CompletableFuture<Void> setGroupDescription(String groupJid, String body) {
        return client.setGroupDescription(groupJid, body, null);
    }

    /**
     * Fetches all WhatsApp groups that the user currently participates in.
     *
     * @return CompletableFuture resolving to list of GroupInfo objects
     */
    public CompletableFuture<List<GroupListQuery.GroupInfo>> queryJoinedGroups() {
        return client.queryJoinedGroups();
    }

    /**
     * Fetches all joined groups with optional cache bypass.
     *
     * @param forceRefresh True to bypass local memory cache and query server directly
     * @return CompletableFuture resolving to list of GroupInfo objects
     */
    public CompletableFuture<List<GroupListQuery.GroupInfo>> queryJoinedGroups(boolean forceRefresh) {
        return client.queryJoinedGroups(forceRefresh);
    }

    /**
     * Fetches detailed group information, participants, and settings for a specific group.
     *
     * @param groupJid Group JID
     * @return CompletableFuture resolving to GroupInfo
     */
    public CompletableFuture<GroupListQuery.GroupInfo> queryGroupInfo(String groupJid) {
        return client.queryGroupInfo(groupJid);
    }

    /**
     * Updates the group avatar picture with raw JPEG data.
     *
     * @param groupJid Group JID
     * @param imageBytes Image JPEG bytes
     * @return CompletableFuture resolving to response BinaryNode
     */
    public CompletableFuture<BinaryNode> updateGroupPicture(String groupJid, byte[] imageBytes) {
        return client.updateGroupPicture(groupJid, imageBytes);
    }

    /**
     * Removes the group avatar picture.
     *
     * @param groupJid Group JID
     * @return CompletableFuture resolving to response BinaryNode
     */
    public CompletableFuture<BinaryNode> removeGroupPicture(String groupJid) {
        return client.removeGroupPicture(groupJid);
    }

    /**
     * Configures the disappearing message timer for this group.
     *
     * @param groupJid Group JID
     * @param timerSeconds Expiration period in seconds (e.g. 86400, 604800, 7776000, 0 to disable)
     * @return CompletableFuture completing when updated
     */
    public CompletableFuture<Void> setDisappearingTimer(String groupJid, int timerSeconds) {
        return client.setDisappearingTimer(groupJid, timerSeconds);
    }

    /**
     * Returns the cached disappearing message timer in seconds for this group.
     *
     * @param groupJid Group JID
     * @return Expiration seconds, or 0 if disabled
     */
    public int getDisappearingTimer(String groupJid) {
        return client.getChatEphemeralTimer(groupJid);
    }

    /**
     * Retrieves or resets the group invite link code.
     *
     * @param groupJid Group JID
     * @param reset True to revoke previous invite code and issue a new one
     * @return CompletableFuture resolving to invite code string
     */
    public CompletableFuture<String> getGroupInviteLink(String groupJid, boolean reset) {
        return client.getGroupInviteLink(groupJid, reset);
    }

    /**
     * Retrieves the current group invite link code.
     *
     * @param groupJid Group JID
     * @return CompletableFuture resolving to invite code string
     */
    public CompletableFuture<String> getGroupInviteLink(String groupJid) {
        return client.getGroupInviteLink(groupJid);
    }

    /**
     * Revokes the existing group invite link code.
     *
     * @param groupJid Group JID
     * @return CompletableFuture resolving to new replacement invite code
     */
    public CompletableFuture<String> revokeGroupInviteLink(String groupJid) {
        return client.revokeGroupInviteLink(groupJid);
    }

    /**
     * Inspects group metadata from an invite link code without joining.
     *
     * @param code Invite link alphanumeric code
     * @return CompletableFuture resolving to server BinaryNode response
     */
    public CompletableFuture<BinaryNode> getGroupInfoFromLink(String code) {
        return client.getGroupInfoFromLink(code);
    }

    /**
     * Joins a group using an invite link code.
     *
     * @param code Invite link alphanumeric code
     * @return CompletableFuture resolving to joined group JID
     */
    public CompletableFuture<String> joinGroupWithLink(String code) {
        return client.joinGroupWithLink(code);
    }

    /**
     * Sets whether only administrators can edit group settings.
     *
     * @param groupJid Group JID
     * @param locked True to lock settings to admins only, false to allow all participants
     * @return CompletableFuture completing when updated
     */
    public CompletableFuture<Void> setGroupLocked(String groupJid, boolean locked) {
        return client.setGroupLocked(groupJid, locked);
    }

    /**
     * Sets whether the group is an announcement-only group where only admins can send messages.
     *
     * @param groupJid Group JID
     * @param announce True for announcement only, false for normal discussion
     * @return CompletableFuture completing when updated
     */
    public CompletableFuture<Void> setGroupAnnounce(String groupJid, boolean announce) {
        return client.setGroupAnnounce(groupJid, announce);
    }

    /**
     * Configures whether admin approval is required for new members joining the group.
     *
     * @param groupJid Group JID
     * @param approvalRequired True to require membership approval, false for open join
     * @return CompletableFuture completing when updated
     */
    public CompletableFuture<Void> setGroupJoinApprovalMode(String groupJid, boolean approvalRequired) {
        return client.setGroupJoinApprovalMode(groupJid, approvalRequired);
    }

    /**
     * Configures whether only admins can add new participants.
     *
     * @param groupJid Group JID
     * @param adminOnly True if only admins can add members, false if all members can add
     * @return CompletableFuture completing when updated
     */
    public CompletableFuture<Void> setGroupMemberAddMode(String groupJid, boolean adminOnly) {
        return client.setGroupMemberAddMode(groupJid, adminOnly);
    }

    /**
     * Retrieves pending membership approval requests for the group.
     *
     * @param groupJid Group JID
     * @return CompletableFuture resolving to list of GroupJoinRequest
     */
    public CompletableFuture<List<GroupJoinRequest>> getGroupRequestParticipants(String groupJid) {
        return client.getGroupRequestParticipants(groupJid);
    }

    /**
     * Approves or rejects pending membership join requests.
     *
     * @param groupJid Group JID
     * @param userJids List of applicant user JIDs
     * @param approve True to approve, false to reject
     * @return CompletableFuture completing when processed
     */
    public CompletableFuture<Void> updateGroupRequestParticipants(String groupJid, List<String> userJids, boolean approve) {
        return client.updateGroupRequestParticipants(groupJid, userJids, approve);
    }

    /**
     * Fetches child discussion subgroups linked under a WhatsApp Community.
     *
     * @param communityJid Community group JID
     * @return CompletableFuture resolving to list of CommunitySubGroup
     */
    public CompletableFuture<List<CommunitySubGroup>> getSubGroups(String communityJid) {
        return client.getSubGroups(communityJid);
    }

    /**
     * Fetches participant JIDs across all groups linked under a community.
     *
     * @param communityJid Community group JID
     * @return CompletableFuture resolving to list of participant JID strings
     */
    public CompletableFuture<List<String>> getLinkedGroupsParticipants(String communityJid) {
        return client.getLinkedGroupsParticipants(communityJid);
    }

    /**
     * Links an existing discussion group under a parent WhatsApp Community.
     *
     * @param communityJid Parent Community JID
     * @param childGroupJid Discussion group JID to link
     * @return CompletableFuture completing when linked
     */
    public CompletableFuture<Void> linkGroup(String communityJid, String childGroupJid) {
        return client.linkGroup(communityJid, childGroupJid);
    }

    /**
     * Unlinks a discussion group from a parent WhatsApp Community.
     *
     * @param communityJid Parent Community JID
     * @param childGroupJid Discussion group JID to unlink
     * @return CompletableFuture completing when unlinked
     */
    public CompletableFuture<Void> unlinkGroup(String communityJid, String childGroupJid) {
        return client.unlinkGroup(communityJid, childGroupJid);
    }

    /**
     * Retrieves group information from a private invitation stanza.
     *
     * @param groupJid Group JID
     * @param inviterJid JID of the inviting administrator
     * @param code Invitation code
     * @param expiration Epoch timestamp seconds when invite expires
     * @return CompletableFuture resolving to response BinaryNode
     */
    public CompletableFuture<BinaryNode> getGroupInfoFromInvite(String groupJid, String inviterJid, String code, long expiration) {
        return client.getGroupInfoFromInvite(groupJid, inviterJid, code, expiration);
    }

    /**
     * Joins a group using a private invitation stanza.
     *
     * @param groupJid Group JID
     * @param inviterJid JID of the inviting administrator
     * @param code Invitation code
     * @param expiration Epoch timestamp seconds when invite expires
     * @return CompletableFuture completing when joined
     */
    public CompletableFuture<Void> joinGroupWithInvite(String groupJid, String inviterJid, String code, long expiration) {
        return client.joinGroupWithInvite(groupJid, inviterJid, code, expiration);
    }
}
