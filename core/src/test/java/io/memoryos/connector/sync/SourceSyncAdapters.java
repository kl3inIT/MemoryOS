package io.memoryos.connector.sync;

import io.memoryos.connector.ConnectorSyncPort.Work;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceType;
import io.memoryos.connector.sync.persistence.JdbcSourceSyncRepository.DueSource;
import io.memoryos.shared.TenantId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Registries for tests that wire the connector by hand. The adapters a test passes are kept; every other external
 * provider gets an inert one, so the registry is complete as it is at startup.
 */
public final class SourceSyncAdapters {
    private SourceSyncAdapters() {
    }

    public static SourceSyncAdapterRegistry registry(SourceSyncAdapter... adapters) {
        var all = new ArrayList<>(List.of(adapters));
        var present = EnumSet.noneOf(SourceType.class);
        for (var adapter : adapters) present.add(adapter.type());
        for (var type : SourceType.values()) {
            if (type.external() && !present.contains(type)) all.add(credentials(type, (_, _, _) -> false));
        }
        return new SourceSyncAdapterRegistry(all);
    }

    /**
     * An adapter that only answers whether a credential still serves a Source. Google Drive is the provider whose
     * permissions are synchronized; an item with a provider file ID opens at {@code https://<type>.test/<file ID>}.
     */
    public static SourceSyncAdapter credentials(SourceType type, CredentialCheck check) {
        return new SourceSyncAdapter() {
            @Override
            public SourceType type() {
                return type;
            }

            @Override
            public SourceProviderCapabilities capabilities() {
                return new SourceProviderCapabilities(type == SourceType.GOOGLE_DRIVE);
            }

            @Override
            public List<DueSource> due(int limit) {
                return List.of();
            }

            @Override
            public long credentialRevision(TenantId tenant, SourceId source) {
                throw new UnsupportedOperationException("credential check only");
            }

            @Override
            public boolean credentialCurrent(TenantId tenant, SourceId source, long credentialRevision) {
                return check.current(tenant, source, credentialRevision);
            }

            @Override
            public @Nullable String documentUrl(@Nullable String providerFileId, @Nullable String sourceUrl) {
                return providerFileId == null ? null
                        : "https://" + type.name().toLowerCase(Locale.ROOT) + ".test/" + providerFileId;
            }

            @Override
            public Slice walk(SyncRun run) {
                throw new UnsupportedOperationException("credential check only");
            }

            @Override
            public RunFailure classify(RuntimeException failure) {
                throw new UnsupportedOperationException("credential check only");
            }

            @Override
            public void authenticationFailed(Work work) {
                throw new UnsupportedOperationException("credential check only");
            }
        };
    }

    @FunctionalInterface
    public interface CredentialCheck {
        boolean current(TenantId tenant, SourceId source, long credentialRevision);
    }
}
