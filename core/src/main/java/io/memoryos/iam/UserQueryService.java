package io.memoryos.iam;

public interface UserQueryService {

    UserPage list(ActorId administrator, UserQuery query);
}
