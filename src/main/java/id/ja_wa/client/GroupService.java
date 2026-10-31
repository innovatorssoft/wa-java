package id.ja_wa.client;

import id.ja_wa.domain.model.CommunitySubGroup;
import id.ja_wa.domain.model.GroupJoinRequest;
import id.ja_wa.feature.community.CommunityQuery;
import id.ja_wa.feature.messaging.GroupAction;
import id.ja_wa.feature.messaging.GroupMetadataQuery;
import id.ja_wa.feature.messaging.GroupListQuery;
import id.ja_wa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    // ---- Increment 3: community queries (Baileys lib/Socket/communities.js) ----
    //
    // These facades send through the existing public sendIqAsync transport.
    // IQ ids are generated locally (UUID hex) because JaWaClient.newIqId is
    // private; a parent hook may replace this with a client-side generator.
    // Where Baileys post-processes a response with a second round trip
    // (e.g. parseGroupResult re-fetching group metadata after create), this
    // facade returns the raw response node instead and says so.

    private static String newIqId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private CompletableFuture<BinaryNode> sendCommunityIq(BinaryNode iq) {
        return client.sendIqAsync(iq);
    }

    /**
     * Creates a community (Baileys {@code communityCreate}).
     * This facade returns the raw create response node; use
     * {@link #createCommunityWithMetadata(String, String)} for Baileys'
     * post-create metadata refetch.
     *
     * @param subject community name
     * @param description community description, or null
     * @return CompletableFuture resolving to the raw response BinaryNode
     */
    public CompletableFuture<BinaryNode> createCommunity(String subject, String description) {
        String descriptionId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return sendCommunityIq(CommunityQuery.buildCreate(newIqId(), subject, description, descriptionId));
    }

    /**
     * Creates a community and re-fetches its metadata, as Baileys'
     * {@code communityCreate} does via {@code parseGroupResult} (the
     * create response's {@code <group>} id is re-queried with
     * {@code groupMetadata}). A create response without a
     * {@code <group>} child, or a failed metadata refetch, resolves
     * to null rather than throwing, matching Baileys' null return
     * from {@code parseGroupResult} on error.
     *
     * @param subject community name
     * @param description community description, or null
     * @return CompletableFuture resolving to the created community's
     *         metadata, or null when the refetch is unavailable
     */
    public CompletableFuture<GroupMetadataQuery.GroupMetadata> createCommunityWithMetadata(
            String subject, String description) {
        return createCommunity(subject, description)
            .thenCompose(resp -> refetchCreatedGroupMetadata(resp));
    }

    /**
     * Creates a subgroup linked to a parent community (Baileys {@code communityCreateGroup}).
     * This facade returns the raw create response node; use
     * {@link #createCommunityGroupWithMetadata(String, List, String)} for
     * Baileys' post-create metadata refetch.
     *
     * @param subject subgroup name
     * @param participants initial participant JIDs
     * @param parentCommunityJid parent community JID
     * @return CompletableFuture resolving to the raw response BinaryNode
     */
    public CompletableFuture<BinaryNode> createCommunityGroup(String subject, List<String> participants,
                                                              String parentCommunityJid) {
        String key = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return sendCommunityIq(CommunityQuery.buildCreateGroup(newIqId(), subject, participants,
            parentCommunityJid, key));
    }

    /**
     * Creates a subgroup linked to a parent community and re-fetches
     * its metadata, as Baileys' {@code communityCreateGroup} does via
     * {@code parseGroupResult}. See
     * {@link #createCommunityWithMetadata(String, String)} for the
     * null-on-unavailable-refetch contract.
     *
     * @param subject subgroup name
     * @param participants initial participant JIDs
     * @param parentCommunityJid parent community JID
     * @return CompletableFuture resolving to the created subgroup's
     *         metadata, or null when the refetch is unavailable
     */
    public CompletableFuture<GroupMetadataQuery.GroupMetadata> createCommunityGroupWithMetadata(
            String subject, List<String> participants, String parentCommunityJid) {
        return createCommunityGroup(subject, participants, parentCommunityJid)
            .thenCompose(resp -> refetchCreatedGroupMetadata(resp));
    }

    /** Baileys {@code parseGroupResult}: re-query metadata for a create response's {@code <group>} id. */
    private CompletableFuture<GroupMetadataQuery.GroupMetadata> refetchCreatedGroupMetadata(
            BinaryNode createResponse) {
        if (createResponse == null) return CompletableFuture.completedFuture(null);
        BinaryNode group = createResponse.child("group");
        if (group == null) return CompletableFuture.completedFuture(null);
        String id = group.attr("id");
        if (id == null || id.isEmpty()) return CompletableFuture.completedFuture(null);
        String jid = id.contains("@") ? id : id + "@g.us";
        return queryGroupMetadata(jid);
    }

    /**
     * Fetches community metadata (Baileys {@code communityMetadata}).
     * Returns the raw response node; for the parsed form use
     * {@link #queryGroupMetadata(String)}, which parses the same
     * {@code w:g2} interactive query with
     * {@code GroupMetadataQuery} (including {@code linkedParent},
     * restrict, announce, memberAddMode and ephemeralDuration).
     *
     * @param communityJid community JID
     * @return CompletableFuture resolving to the raw response BinaryNode
     */
    public CompletableFuture<BinaryNode> getCommunityMetadata(String communityJid) {
        return sendCommunityIq(CommunityQuery.buildMetadata(newIqId(), communityJid));
    }

    /**
     * Fetches all participating communities (Baileys {@code communityFetchAllParticipating}).
     * Baileys emits {@code groups.update} for these; this facade only returns
     * the raw response node and does not dispatch.
     *
     * @return CompletableFuture resolving to the raw response BinaryNode
     */
    public CompletableFuture<BinaryNode> fetchAllParticipatingCommunities() {
        return sendCommunityIq(CommunityQuery.buildFetchAllParticipating(newIqId()));
    }

    /**
     * Leaves a community (Baileys {@code communityLeave}).
     *
     * @param communityId community id (bare id, as Baileys sends it)
     * @return CompletableFuture completing when left
     */
    public CompletableFuture<Void> leaveCommunity(String communityId) {
        return sendCommunityIq(CommunityQuery.buildLeave(newIqId(), communityId))
            .thenApply(resp -> null);
    }

    /**
     * Updates a community subject (Baileys {@code communityUpdateSubject}).
     *
     * @param communityJid community JID
     * @param subject new subject
     * @return CompletableFuture completing when updated
     */
    public CompletableFuture<Void> updateCommunitySubject(String communityJid, String subject) {
        return sendCommunityIq(CommunityQuery.buildUpdateSubject(newIqId(), communityJid, subject))
            .thenApply(resp -> null);
    }

    /**
     * Updates a community description (Baileys {@code communityUpdateDescription}).
     * Baileys reads the previous description id from metadata; callers pass
     * it here, or null to omit {@code prev}. A null description deletes it.
     *
     * @param communityJid community JID
     * @param description new description, or null to delete
     * @param previousDescId previous description id, or null
     * @return CompletableFuture completing when updated
     */
    public CompletableFuture<Void> updateCommunityDescription(String communityJid, String description,
                                                              String previousDescId) {
        String newId = description == null ? null
            : UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return sendCommunityIq(CommunityQuery.buildUpdateDescription(newIqId(), communityJid,
            description, previousDescId, newId)).thenApply(resp -> null);
    }

    /**
     * Updates a community setting by bare tag name (Baileys {@code communitySettingUpdate}).
     *
     * @param communityJid community JID
     * @param setting setting tag (e.g. {@code locked}, {@code announcement})
     * @return CompletableFuture completing when updated
     */
    public CompletableFuture<Void> updateCommunitySetting(String communityJid, String setting) {
        return sendCommunityIq(CommunityQuery.buildSettingUpdate(newIqId(), communityJid, setting))
            .thenApply(resp -> null);
    }

    /**
     * Sets who may add members (Baileys {@code communityMemberAddMode}).
     *
     * @param communityJid community JID
     * @param mode mode string passed through verbatim (e.g. {@code all_member_add})
     * @return CompletableFuture completing when updated
     */
    public CompletableFuture<Void> setCommunityMemberAddMode(String communityJid, String mode) {
        return sendCommunityIq(CommunityQuery.buildMemberAddMode(newIqId(), communityJid, mode))
            .thenApply(resp -> null);
    }

    /**
     * Sets the community join-approval mode (Baileys {@code communityJoinApprovalMode}).
     *
     * @param communityJid community JID
     * @param mode mode state string passed through verbatim
     * @return CompletableFuture completing when updated
     */
    public CompletableFuture<Void> setCommunityJoinApprovalMode(String communityJid, String mode) {
        return sendCommunityIq(CommunityQuery.buildJoinApprovalMode(newIqId(), communityJid, mode))
            .thenApply(resp -> null);
    }

    /**
     * Toggles the community disappearing-message timer (Baileys {@code communityToggleEphemeral}).
     *
     * @param communityJid community JID
     * @param ephemeralExpiration expiration seconds, or null to disable
     * @return CompletableFuture completing when updated
     */
    public CompletableFuture<Void> toggleCommunityEphemeral(String communityJid, Long ephemeralExpiration) {
        return sendCommunityIq(CommunityQuery.buildToggleEphemeral(newIqId(), communityJid, ephemeralExpiration))
            .thenApply(resp -> null);
    }

    /**
     * Fetches the community invite code (Baileys {@code communityInviteCode}).
     *
     * @param communityJid community JID
     * @return CompletableFuture resolving to the invite code, or null when absent
     */
    public CompletableFuture<String> getCommunityInviteCode(String communityJid) {
        return sendCommunityIq(CommunityQuery.buildInviteCode(newIqId(), communityJid))
            .thenApply(CommunityQuery::parseInviteCode);
    }

    /**
     * Revokes the community invite code and returns the replacement (Baileys {@code communityRevokeInvite}).
     *
     * @param communityJid community JID
     * @return CompletableFuture resolving to the new invite code, or null when absent
     */
    public CompletableFuture<String> revokeCommunityInvite(String communityJid) {
        return sendCommunityIq(CommunityQuery.buildRevokeInvite(newIqId(), communityJid))
            .thenApply(CommunityQuery::parseInviteCode);
    }

    /**
     * Accepts a community invite (Baileys {@code communityAcceptInvite}).
     *
     * @param code invite code
     * @return CompletableFuture resolving to the community JID, or null when absent
     */
    public CompletableFuture<String> acceptCommunityInvite(String code) {
        return sendCommunityIq(CommunityQuery.buildAcceptInvite(newIqId(), code))
            .thenApply(CommunityQuery::parseAcceptInviteCommunityJid);
    }

    /**
     * Increment 6: accepts a community V4 invite (Baileys
     * {@code communityAcceptInviteV4}) and, when a full message key is
     * supplied, emits the expired-invite {@code messages.update}
     * (see {@code JaWaClient.acceptCommunityInviteV4} for the exact
     * emission rule; Increment 8 adds the synthetic
     * GROUP_PARTICIPANT_ADD upsert and store feed there).
     * Delegates to the client, which owns the event dispatch.
     *
     * @param inviterRemoteJid inviter's JID (Baileys' {@code key.remoteJid}, the {@code admin} attr)
     * @param messageId invite message id, or null/blank for a bare-JID key (no emission)
     * @param fromMe Baileys key {@code fromMe} for the emitted update
     * @param participant Baileys key {@code participant}, or null
     * @param inviteMessage the invite message to accept
     * @return CompletableFuture resolving to the community JID from the response, or null
     */
    public CompletableFuture<String> acceptCommunityInviteV4(String inviterRemoteJid, String messageId,
                                                              boolean fromMe, String participant,
                                                              id.ja_wa.proto.Wa.Message.GroupInviteMessage inviteMessage) {
        return client.acceptCommunityInviteV4(inviterRemoteJid, messageId, fromMe, participant, inviteMessage);
    }

    /**
     * Increment 7: revokes a community V4 invite (Baileys
     * {@code communityRevokeInviteV4}). Delegates to the client,
     * mirroring the Increment 6 accept-V4 facade: the IQ is built by
     * {@code CommunityQuery.buildRevokeInviteV4} and Baileys returns
     * true for any non-null result.
     *
     * @param communityJid community JID
     * @param invitedJid JID of the invited person whose invite is revoked
     * @return CompletableFuture resolving to true on a response, false on a null response
     */
    public CompletableFuture<Boolean> revokeCommunityInviteV4(String communityJid, String invitedJid) {
        return client.revokeCommunityInviteV4(communityJid, invitedJid);
    }

    /**
     * Increment 6: creates a call link (Baileys {@code createCallLink},
     * {@code lib/Socket/chats.js:603}) via the client's generic
     * {@code <call>} stanza request/response transport. Delegates to
     * {@code JaWaClient.createCallLink}.
     *
     * @param mediaType media type, e.g. {@code video} or {@code audio}
     * @param eventStartTime event start time in epoch seconds, or null for a link with no event
     * @return CompletableFuture resolving to the created link token, or null when the response carries none
     */
    public CompletableFuture<String> createCallLink(String mediaType, Long eventStartTime) {
        return client.createCallLink(mediaType, eventStartTime);
    }

    /**
     * Fetches community metadata from an invite code (Baileys {@code communityGetInviteInfo}).
     * Returns the raw response node for the same reason as {@link #getCommunityMetadata(String)}.
     *
     * @param code invite code
     * @return CompletableFuture resolving to the raw response BinaryNode
     */
    public CompletableFuture<BinaryNode> getCommunityInviteInfo(String code) {
        return sendCommunityIq(CommunityQuery.buildGetInviteInfo(newIqId(), code));
    }

    /**
     * Updates community participants (Baileys {@code communityParticipantsUpdate}).
     * A {@code remove} action also unlinks subgroups (Baileys sends
     * {@code linked_groups: 'true'} for remove).
     *
     * @param communityJid community JID
     * @param participants participant JIDs
     * @param action action tag (e.g. {@code add}, {@code remove}, {@code promote}, {@code demote})
     * @return CompletableFuture resolving to per-participant results (missing error defaults to {@code 200})
     */
    public CompletableFuture<List<CommunityQuery.ParticipantResult>> updateCommunityParticipants(
            String communityJid, List<String> participants, String action) {
        return sendCommunityIq(CommunityQuery.buildParticipantsUpdate(newIqId(), communityJid, participants, action))
            .thenApply(resp -> CommunityQuery.parseParticipantsResult(resp, action));
    }

    /**
     * Fetches pending membership approval requests (Baileys {@code communityRequestParticipantsList}).
     * Returns the raw response node.
     *
     * @param communityJid community JID
     * @return CompletableFuture resolving to the raw response BinaryNode
     */
    public CompletableFuture<BinaryNode> getCommunityRequestParticipants(String communityJid) {
        return sendCommunityIq(CommunityQuery.buildRequestParticipantsList(newIqId(), communityJid));
    }

    /**
     * Approves or rejects pending membership requests (Baileys {@code communityRequestParticipantsUpdate}).
     * Baileys passes the action tag through verbatim; this facade maps
     * approve to {@code approve} and reject to {@code reject}.
     *
     * @param communityJid community JID
     * @param participants applicant JIDs
     * @param approve true to approve, false to reject
     * @return CompletableFuture resolving to per-participant results
     */
    public CompletableFuture<List<CommunityQuery.ParticipantResult>> updateCommunityRequestParticipants(
            String communityJid, List<String> participants, boolean approve) {
        String action = approve ? "approve" : "reject";
        return sendCommunityIq(CommunityQuery.buildRequestParticipantsUpdate(newIqId(), communityJid,
            participants, action))
            .thenApply(resp -> CommunityQuery.parseParticipantsResult(resp, action));
    }

    /**
     * Fetches subgroups linked under a community (Baileys {@code communityFetchLinkedGroups},
     * {@code <sub_groups>} response only). Callers must pass the community
     * JID itself; use {@link #fetchLinkedGroups(String)} when the JID may
     * be a subgroup, which resolves its community first.
     *
     * @param communityJid community JID
     * @return CompletableFuture resolving to the linked subgroups
     */
    public CompletableFuture<List<CommunityQuery.LinkedGroup>> getCommunityLinkedGroups(String communityJid) {
        BinaryNode iq = new BinaryNode("iq",
            Map.of("to", communityJid, "type", "get", "xmlns", "w:g2", "id", newIqId()),
            List.of(new BinaryNode("sub_groups", Map.of(), null)));
        return sendCommunityIq(iq).thenApply(CommunityQuery::parseLinkedGroups);
    }

    /**
     * Baileys {@code communityFetchLinkedGroups} in full: given any group
     * JID, resolve its community via {@code groupMetadata.linkedParent}
     * (a subgroup resolves to its parent; a community resolves to
     * itself), then fetch that community's linked subgroups.
     * {@code isCommunity} reports whether the queried JID was itself
     * the community. A metadata query that fails (null metadata)
     * fails this future rather than guessing a community JID, since
     * Baileys' {@code groupMetadata} throws in that case.
     *
     * @param jid subgroup or community JID
     * @return CompletableFuture resolving to the community JID, the
     *         community flag, and the linked subgroups
     */
    public CompletableFuture<CommunityQuery.LinkedGroupsResult> fetchLinkedGroups(String jid) {
        return queryGroupMetadata(jid).thenCompose(metadata -> {
            if (metadata == null) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("group metadata unavailable for " + jid));
            }
            String communityJid = CommunityQuery.resolveCommunityJid(jid, metadata.linkedParent());
            boolean isCommunity = metadata.linkedParent() == null || metadata.linkedParent().isBlank();
            return getCommunityLinkedGroups(communityJid)
                .thenApply(groups -> new CommunityQuery.LinkedGroupsResult(
                    communityJid, isCommunity, groups));
        });
    }

    /**
     * Fetches full group metadata for a single group (Baileys
     * {@code groupMetadata}, {@code w:g2} interactive query), parsed with
     * full fidelity — subject, owner, description, ephemeral, addressing
     * mode, restrict/announce, community flags, and participants with
     * LID/PN/admin. This is the single-group query that the pre-existing
     * {@code GroupListQuery} participating-list query lacked.
     *
     * @param groupJid group JID
     * @return CompletableFuture resolving to the parsed metadata, or null
     *         when the server rejects the query or returns no group
     */
    public CompletableFuture<GroupMetadataQuery.GroupMetadata> queryGroupMetadata(String groupJid) {
        return client.sendIqAsync(GroupMetadataQuery.buildQuery(newIqId(), groupJid))
            .thenApply(resp -> {
                if ("error".equals(resp.attr("type"))) return null;
                try { return GroupMetadataQuery.parse(resp); }
                catch (RuntimeException e) { return null; }
            });
    }
}
