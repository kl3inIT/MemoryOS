package io.memoryos.iam;

import io.memoryos.iam.identityprovider.persistence.JitAllowlistRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time seed: deployment aliases from {@code MEMORYOS_JIT_ALLOWED_PROVIDER_ALIASES} become durable allowlist rows.
 * Runtime administration owns the table afterwards; the seed is idempotent and never removes runtime-managed entries.
 */
@Service
public class JitAllowlistSeeder {

    private final JitAllowlistRepository allowlist;

    public JitAllowlistSeeder(JitAllowlistRepository allowlist) {
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist must not be null");
    }

    @Transactional
    public void seed(Iterable<String> aliases) {
        for (String alias : aliases) {
            allowlist.allow(alias, null);
        }
    }
}
