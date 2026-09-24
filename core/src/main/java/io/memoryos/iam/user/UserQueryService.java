package io.memoryos.iam.user;
import io.memoryos.shared.ActorId;

public interface UserQueryService {

    UserPage list(ActorId administrator, UserQuery query);
}
