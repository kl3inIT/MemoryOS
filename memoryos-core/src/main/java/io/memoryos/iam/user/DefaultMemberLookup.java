package io.memoryos.iam.user;

import io.memoryos.iam.MemberLookup;
import io.memoryos.iam.user.persistence.UserQueryRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultMemberLookup implements MemberLookup {

    private final UserQueryRepository users;

    public DefaultMemberLookup(UserQueryRepository users) {
        this.users = Objects.requireNonNull(users, "users must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ActorId> activeMemberByVerifiedEmail(TenantId tenantId, String email) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        String normalized = Objects.requireNonNull(email, "email must not be null").strip().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        List<ActorId> holders = users.activeMembersByVerifiedEmail(tenantId, normalized);
        return holders.size() == 1 ? Optional.of(holders.getFirst()) : Optional.empty();
    }
}
