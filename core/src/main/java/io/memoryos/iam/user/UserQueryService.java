package io.memoryos.iam.user;
import io.memoryos.iam.identity.ActorId;

public interface UserQueryService {

    UserPage list(ActorId administrator, UserQuery query);
}
