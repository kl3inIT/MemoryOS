package io.memoryos.iam.identityprovider;

import io.memoryos.iam.JitAdmissionPolicy;
import io.memoryos.iam.identityprovider.persistence.JitAllowlistRepository;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultJitAdmissionPolicy implements JitAdmissionPolicy {

    private final JitAllowlistRepository allowlist;

    public DefaultJitAdmissionPolicy(JitAllowlistRepository allowlist) {
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean allows(Object claim) {
        return claim instanceof String alias && !alias.isBlank() && allowlist.isAllowed(alias);
    }
}
