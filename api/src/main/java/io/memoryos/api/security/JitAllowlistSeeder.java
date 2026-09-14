package io.memoryos.api.security;

import io.memoryos.iam.identityprovider.persistence.JitAllowlistRepository;

import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-time seed: deployment aliases from {@code MEMORYOS_JIT_ALLOWED_PROVIDER_ALIASES} become
 * durable allowlist rows. Runtime administration owns the table afterwards; the seed is idempotent
 * and never removes runtime-managed entries.
 */
@Component
final class JitAllowlistSeeder {

    private final JitAllowlistRepository allowlist;
    private final TransactionTemplate transactions;

    JitAllowlistSeeder(
            JitAllowlistRepository allowlist,
            PlatformTransactionManager transactionManager
    ) {
        this.allowlist = Objects.requireNonNull(allowlist, "allowlist must not be null");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(
                transactionManager,
                "transactionManager must not be null"
        ));
    }

    void seed(Iterable<String> aliases) {
        transactions.executeWithoutResult(_ -> {
            for (String alias : aliases) {
                allowlist.allow(alias, null);
            }
        });
    }
}
