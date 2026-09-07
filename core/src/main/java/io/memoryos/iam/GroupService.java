package io.memoryos.iam;

import java.util.Collection;
import java.util.List;

public interface GroupService {

    GroupPage list(ActorId actorId, GroupQuery query);

    List<GroupCapabilityMetadata> capabilities(ActorId actorId);

    GroupSummary create(ActorId actorId, String name);

    GroupSummary get(ActorId actorId, GroupId groupId);

    GroupSummary rename(ActorId actorId, GroupId groupId, String name);

    void delete(ActorId actorId, GroupId groupId);

    GroupMemberPage members(ActorId actorId, GroupId groupId, GroupQuery query);

    GroupMemberPage candidates(ActorId actorId, GroupId groupId, GroupQuery query);

    void addMembers(ActorId actorId, GroupId groupId, Collection<ActorId> actorIds);

    void removeMember(ActorId actorId, GroupId groupId, ActorId memberActorId);

    void assignManager(ActorId actorId, GroupId groupId, ActorId memberActorId);

    void removeManager(ActorId actorId, GroupId groupId, ActorId memberActorId);

    void replaceCapabilities(
            ActorId actorId,
            GroupId groupId,
            Collection<IamCapability> capabilities
    );

    void replaceOrdinaryMemberships(
            ActorId actorId,
            ActorId memberActorId,
            Collection<GroupId> groupIds
    );
}
