package io.memoryos.retrieval.settings;

import io.memoryos.document.DocumentChunk;
import io.memoryos.document.DocumentChunkPort;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.retrieval.SearchUnavailableException;
import io.memoryos.retrieval.embedding.OpenAiCompatibleEmbeddings;
import io.memoryos.retrieval.opensearch.OpenSearchIndexService;
import io.memoryos.retrieval.opensearch.SearchProperties;
import io.memoryos.retrieval.settings.persistence.JdbcSearchSettingsRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Administration of the embedding model and the lifecycle of its indexes (MEM-135): a FUTURE generation is rebuilt
 * beside PRESENT from stored chunks, switched when it holds every document, cancelled, restored from PAST within the
 * retention period, and its index is deleted once that period ends.
 *
 * <p>The index is shared by every Tenant, so the configuration belongs to the deployment: only a model manager of the
 * operating Tenant may read or change it. Provider calls never run inside a database transaction; the FUTURE index is
 * created inside the transaction that records the generation, so a failure leaves neither behind.
 */
@Service
public class SearchSettingsService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchSettingsService.class);
    /** How long a replaced index is kept for restoring it. */
    public static final Duration RETENTION = Duration.ofDays(7);
    /** Failed index deletions before a PAST generation is shown as blocked. */
    static final int CLEANUP_BLOCK_AFTER = 3;

    private final JdbcSearchSettingsRepository settings;
    private final SearchGenerations generations;
    private final OpenSearchIndexService index;
    private final DocumentChunkPort documents;
    private final IamAuthorization authorization;
    private final TenantAccessResolver tenants;
    private final EmbeddingProviderCredentials credentials;
    private final EmbeddingProbe probe;
    private final SearchProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public SearchSettingsService(JdbcSearchSettingsRepository settings, SearchGenerations generations,
            OpenSearchIndexService index, DocumentChunkPort documents, IamAuthorization authorization,
            TenantAccessResolver tenants, EmbeddingProviderCredentials credentials, EmbeddingProbe probe,
            SearchProperties properties, PlatformTransactionManager transactions) {
        this(settings, generations, index, documents, authorization, tenants, credentials, probe, properties,
                transactions, Clock.systemUTC());
    }

    SearchSettingsService(JdbcSearchSettingsRepository settings, SearchGenerations generations,
            OpenSearchIndexService index, DocumentChunkPort documents, IamAuthorization authorization,
            TenantAccessResolver tenants, EmbeddingProviderCredentials credentials, EmbeddingProbe probe,
            SearchProperties properties, PlatformTransactionManager transactions, Clock clock) {
        this.settings = settings; this.generations = generations; this.index = index; this.documents = documents;
        this.authorization = authorization; this.tenants = tenants; this.credentials = credentials; this.probe = probe;
        this.properties = properties; this.transactions = new TransactionTemplate(transactions); this.clock = clock;
    }

    public record GenerationView(SearchGeneration generation, String providerName, EmbeddingProvider.DataBoundary dataBoundary,
            long documentCount, boolean cleanupBlocked) { }

    /** Documents over the servable corpus, and whether the rebuilt index may replace PRESENT. */
    public record RebuildProgress(long ready, long total, long failed, long pending, @Nullable Long estimatedSecondsRemaining,
            boolean switchable) { }

    public record Settings(GenerationView present, @Nullable GenerationView future, List<GenerationView> past,
            @Nullable RebuildProgress rebuild) { }

    public record ProviderView(UUID id, String name, String endpoint, EmbeddingProvider.DataBoundary dataBoundary,
            boolean hasApiKey, long revision, boolean inUse) { }

    /** {@code apiKey} null keeps the stored key and an empty one removes it; {@code revision} is required to update. */
    public record ProviderInput(String name, String endpoint, @Nullable String apiKey, EmbeddingProvider.DataBoundary dataBoundary,
            @Nullable Long revision) {
        @Override public @NonNull String toString() { return "ProviderInput[name=" + name + ", endpoint=" + endpoint + ", apiKey=REDACTED]"; }
    }

    public record GenerationInput(UUID providerId, String model, int dimensions, String queryPrefix, String documentPrefix,
            double minimumSemanticScore) { }

    /** A saved provider, optionally with an edited endpoint or key, or an unsaved endpoint and key. */
    public record TestInput(@Nullable UUID providerId, @Nullable String endpoint, @Nullable String apiKey, String model,
            @Nullable Integer dimensions) {
        @Override public @NonNull String toString() { return "TestInput[providerId=" + providerId + ", model=" + model + ", apiKey=REDACTED]"; }
    }

    // ---- reads ----

    public Settings settings(ActorId actor) {
        return Objects.requireNonNull(transactions.execute(_ -> {
            UUID tenant = authorize(actor, false);
            return view(tenant);
        }));
    }

    private Settings view(UUID tenant) {
        var rows = settings.generations(tenant);
        Instant now = clock.instant();
        GenerationView present = null, future = null;
        var past = new java.util.ArrayList<GenerationView>();
        for (var row : rows) {
            var view = new GenerationView(row.generation(), row.providerName(), row.dataBoundary(), row.documentCount(),
                    row.cleanupBlocked());
            switch (row.generation().status()) {
                case PRESENT -> present = view;
                case FUTURE -> future = view;
                case PAST -> {
                    var until = row.generation().retainedUntil();
                    if (row.cleanupBlocked() || until != null && until.isAfter(now)) past.add(view);
                }
            }
        }
        if (present == null) throw new SearchUnavailableException();
        return new Settings(present, future, List.copyOf(past),
                future == null ? null : progress(future.generation(), present.generation(), now));
    }

    private RebuildProgress progress(SearchGeneration future, SearchGeneration present, Instant now) {
        var counts = settings.progress(future.identity(), present.identity());
        long pending = counts.pending();
        Long remaining = null;
        if (pending == 0) remaining = 0L;
        else if (counts.ready() > 0) {
            long elapsed = Math.max(1, Duration.between(future.createdAt(), now).toSeconds());
            remaining = (long) Math.ceil((double) pending * elapsed / counts.ready());
        }
        boolean switchable = pending == 0 && counts.ready() >= counts.presentReady();
        return new RebuildProgress(counts.ready(), counts.total(), counts.failed(), pending, remaining, switchable);
    }

    public List<ProviderView> providers(ActorId actor) {
        return Objects.requireNonNull(transactions.execute(_ -> {
            UUID tenant = authorize(actor, false);
            var used = settings.providersInUse(tenant);
            return settings.providers(tenant).stream().map(provider -> view(provider, used.contains(provider.id()))).toList();
        }));
    }

    private ProviderView view(EmbeddingProvider provider, boolean inUse) {
        return new ProviderView(provider.id(), provider.name(), provider.endpoint(), provider.dataBoundary(),
                credentials.present(provider.credential()), provider.revision(), inUse);
    }

    public List<EmbeddingModelPreset> presets(ActorId actor) {
        transactions.executeWithoutResult(_ -> authorize(actor, false));
        return EmbeddingModelPreset.KNOWN;
    }

    // ---- providers ----

    public ProviderView createProvider(ActorId actor, ProviderInput input) {
        return Objects.requireNonNull(transactions.execute(_ -> {
            UUID tenant = authorize(actor, true);
            settings.lock(tenant);
            UUID id = UUID.randomUUID();
            String name = name(input.name());
            if (settings.nameTaken(tenant, name, null)) throw SearchSettingsException.duplicateName();
            String key = input.apiKey();
            var provider = provider(id, tenant, name, input.endpoint(),
                    key == null || key.isEmpty() ? null : credentials.seal(tenant, id, key), input.dataBoundary(), 1);
            settings.insertProvider(provider);
            LOGGER.atInfo().addKeyValue("event", "search.embedding_provider.created").addKeyValue("provider", id).log("Embedding provider created");
            return view(provider, false);
        }));
    }

    public ProviderView updateProvider(ActorId actor, UUID providerId, ProviderInput input) {
        var updated = Objects.requireNonNull(transactions.execute(_ -> {
            UUID tenant = authorize(actor, true);
            settings.lock(tenant);
            var existing = settings.provider(tenant, providerId).orElseThrow(SearchSettingsException::notFound);
            if (input.revision() == null || input.revision() < 1) throw SearchSettingsException.invalid("The revision is required.");
            if (existing.revision() != input.revision()) throw SearchSettingsException.staleRevision();
            String name = name(input.name());
            if (settings.nameTaken(tenant, name, providerId)) throw SearchSettingsException.duplicateName();
            String key = input.apiKey();
            String credential = key == null ? existing.credential() : key.isEmpty() ? null : credentials.seal(tenant, providerId, key);
            var provider = provider(providerId, tenant, name, input.endpoint(), credential, input.dataBoundary(),
                    existing.revision() + 1);
            if (!settings.updateProvider(provider, existing.revision())) throw SearchSettingsException.staleRevision();
            return view(provider, settings.providersInUse(tenant).contains(providerId));
        }));
        // A generation's client is built from its provider; this process rebuilds it now, the others within a refresh.
        generations.invalidate();
        return updated;
    }

    public void deleteProvider(ActorId actor, UUID providerId) {
        transactions.executeWithoutResult(_ -> {
            UUID tenant = authorize(actor, true);
            settings.lock(tenant);
            if (settings.provider(tenant, providerId).isEmpty()) throw SearchSettingsException.notFound();
            if (settings.providersInUse(tenant).contains(providerId)) throw SearchSettingsException.providerInUse();
            settings.deleteProvider(tenant, providerId);
        });
    }

    public EmbeddingProbe.Outcome testProvider(ActorId actor, TestInput input) {
        if (input.model() == null || input.model().isBlank() || input.model().length() > 200) {
            throw SearchSettingsException.invalid("The model name is required.");
        }
        if (input.dimensions() != null && (input.dimensions() < 1 || input.dimensions() > 16000)) {
            throw SearchSettingsException.invalid("Dimensions must be between 1 and 16000.");
        }
        record Connection(String endpoint, String key) { }
        var connection = Objects.requireNonNull(transactions.execute(_ -> {
            UUID tenant = authorize(actor, false);
            if (input.providerId() == null) {
                if (input.endpoint() == null) throw SearchSettingsException.invalid("The endpoint is required.");
                return new Connection(endpoint(input.endpoint()), keyOrNone(input.apiKey()));
            }
            var provider = settings.provider(tenant, input.providerId()).orElseThrow(SearchSettingsException::notFound);
            String endpoint = endpoint(input.endpoint() == null ? provider.endpoint() : input.endpoint());
            String key = input.apiKey() != null ? keyOrNone(input.apiKey()) : storedKey(provider);
            return new Connection(endpoint, key);
        }));
        return probe.probe(connection.endpoint(), connection.key(), input.model().trim(), input.dimensions());
    }

    // ---- generations ----

    /**
     * Starts rebuilding the index for another model. One real embedding call first proves the provider serves the
     * model with the dimensions asked; the generation and its index are then written together.
     */
    public GenerationView createFuture(ActorId actor, GenerationInput input) {
        validate(input);
        record Start(UUID tenant, EmbeddingProvider provider) { }
        var start = Objects.requireNonNull(transactions.execute(_ -> {
            UUID tenant = authorize(actor, false);
            if (settings.future(tenant).isPresent()) throw SearchSettingsException.futureExists();
            return new Start(tenant, settings.provider(tenant, input.providerId()).orElseThrow(SearchSettingsException::notFound));
        }));
        var outcome = probe.probe(start.provider().endpoint(), storedKey(start.provider()), input.model(), input.dimensions());
        if (!outcome.ok()) {
            if (outcome.failure() == EmbeddingProbe.Failure.UNREACHABLE) throw new SearchUnavailableException();
            throw SearchSettingsException.embeddingRejected(Objects.requireNonNull(outcome.error()));
        }
        UUID id = UUID.randomUUID();
        var generation = new SearchGeneration(id, start.tenant(), input.providerId(), input.model(), input.dimensions(),
                input.queryPrefix(), input.documentPrefix(), input.minimumSemanticScore(), DocumentChunk.CONVENTION,
                SearchGeneration.identityFor(properties.indexPrefix(), id), SearchGeneration.Status.FUTURE, false,
                clock.instant(), null, null);
        begin(generation, () -> {
            UUID tenant = authorize(actor, true);
            if (!tenant.equals(start.tenant())) throw SearchSettingsException.notPermitted();
        });
        LOGGER.atInfo().addKeyValue("event", "search.generation.rebuild_started").addKeyValue("generation", id)
                .addKeyValue("identity", generation.identity()).addKeyValue("model", generation.model())
                .addKeyValue("dimensions", generation.dimensions()).addKeyValue("automatic", false)
                .log("Started rebuilding the search index for a new embedding model");
        return Objects.requireNonNull(transactions.execute(_ -> view(start.tenant()).future()));
    }

    /** Records the FUTURE generation and creates its index in one transaction; a failed step leaves neither. */
    private void begin(SearchGeneration generation, Runnable authorizeInTransaction) {
        try {
            transactions.executeWithoutResult(_ -> {
                authorizeInTransaction.run();
                settings.lock(generation.tenantId());
                if (settings.future(generation.tenantId()).isPresent()) throw SearchSettingsException.futureExists();
                var provider = settings.provider(generation.tenantId(), generation.providerId())
                        .orElseThrow(SearchSettingsException::notFound);
                settings.insertGeneration(generation);
                index.createIndex(generations.client(generation, provider));
            });
        } catch (RuntimeException failure) {
            try { index.deleteIndex(generation.identity()); }
            catch (RuntimeException ignored) { /* nothing was created, or cleanup is left to an operator */ }
            throw failure;
        } finally {
            generations.invalidate();
        }
    }

    /** Cancels the rebuild: the FUTURE stops receiving work and its index is deleted. */
    public void cancelFuture(ActorId actor) {
        var cancelled = Objects.requireNonNull(transactions.execute(_ -> {
            UUID tenant = authorize(actor, true);
            settings.lock(tenant);
            var future = settings.future(tenant).orElseThrow(SearchSettingsException::notFound);
            // Retained until now: nothing can restore it, and cleanup deletes its index and then the generation.
            settings.retire(tenant, future.id(), clock.instant());
            settings.cancelWork(future.identity());
            return future;
        }));
        generations.invalidate();
        LOGGER.atInfo().addKeyValue("event", "search.generation.rebuild_cancelled").addKeyValue("generation", cancelled.id())
                .addKeyValue("identity", cancelled.identity()).log("Cancelled the search index rebuild");
        cleanup(settings.generation(cancelled.tenantId(), cancelled.id()).orElse(null));
    }

    /** Makes the complete FUTURE PRESENT; PRESENT becomes PAST and is kept for {@link #RETENTION}. */
    public Settings switchFuture(ActorId actor) {
        var tenant = Objects.requireNonNull(transactions.execute(_ -> {
            UUID operating = authorize(actor, true);
            switchLocked(operating, false);
            return operating;
        }));
        generations.invalidate();
        return Objects.requireNonNull(transactions.execute(_ -> view(tenant)));
    }

    /** Switches an automatic rebuild once it holds every document; no administrator acts on it. */
    public boolean switchAutomaticWhenComplete() {
        var operating = tenants.operatingTenant();
        if (operating.isEmpty()) return false;
        UUID tenant = operating.orElseThrow().value();
        var future = settings.future(tenant);
        if (future.isEmpty() || !future.orElseThrow().automatic()) return false;
        boolean switched = Boolean.TRUE.equals(transactions.execute(_ -> {
            try { switchLocked(tenant, true); return true; }
            catch (SearchSettingsException notYet) { return false; }
        }));
        if (switched) generations.invalidate();
        return switched;
    }

    private void switchLocked(UUID tenant, boolean automaticOnly) {
        settings.lock(tenant);
        var future = settings.future(tenant).orElseThrow(SearchSettingsException::notFound);
        if (automaticOnly && !future.automatic()) throw SearchSettingsException.incomplete();
        var present = settings.present(tenant).orElseThrow(SearchUnavailableException::new);
        Instant now = clock.instant();
        if (!progress(future, present, now).switchable()) throw SearchSettingsException.incomplete();
        settings.retire(tenant, present.id(), now.plus(RETENTION));
        settings.activate(tenant, future.id(), now);
        documents.serve(future.identity());
        LOGGER.atInfo().addKeyValue("event", "search.generation.switched").addKeyValue("generation", future.id())
                .addKeyValue("identity", future.identity()).addKeyValue("previous_identity", present.identity())
                .addKeyValue("model", future.model()).addKeyValue("automatic", future.automatic())
                .log("The rebuilt search index is now PRESENT");
    }

    /** Makes a PAST generation within its retention PRESENT again; the current PRESENT becomes PAST. */
    public Settings restorePast(ActorId actor, UUID generationId) {
        var target = Objects.requireNonNull(transactions.execute(_ -> {
            UUID tenant = authorize(actor, false);
            var generation = settings.generation(tenant, generationId)
                    .filter(value -> value.status() == SearchGeneration.Status.PAST)
                    .orElseThrow(SearchSettingsException::notFound);
            return restorable(generation);
        }));
        // The index must still exist: a PAST generation whose cleanup started cannot come back.
        if (!index.indexExists(target.identity())) throw SearchSettingsException.retentionEnded();
        transactions.executeWithoutResult(_ -> {
            UUID tenant = authorize(actor, true);
            settings.lock(tenant);
            var generation = restorable(settings.generation(tenant, generationId)
                    .filter(value -> value.status() == SearchGeneration.Status.PAST)
                    .orElseThrow(SearchSettingsException::notFound));
            var present = settings.present(tenant).orElseThrow(SearchUnavailableException::new);
            Instant now = clock.instant();
            settings.retire(tenant, present.id(), now.plus(RETENTION));
            settings.activate(tenant, generation.id(), now);
            documents.serve(generation.identity());
            LOGGER.atInfo().addKeyValue("event", "search.generation.restored").addKeyValue("generation", generation.id())
                    .addKeyValue("identity", generation.identity()).addKeyValue("previous_identity", present.identity())
                    .log("Restored a retained search index as PRESENT");
        });
        generations.invalidate();
        return Objects.requireNonNull(transactions.execute(_ -> view(target.tenantId())));
    }

    private SearchGeneration restorable(SearchGeneration generation) {
        if (settings.future(generation.tenantId()).isPresent()) throw SearchSettingsException.futureExists();
        var until = generation.retainedUntil();
        if (until == null || !until.isAfter(clock.instant())) throw SearchSettingsException.retentionEnded();
        return generation;
    }

    /**
     * A release whose chunk convention differs from PRESENT's rebuilds the index in the background with the same model
     * and switches itself when complete, so search keeps serving PRESENT meanwhile. The api and the worker both start;
     * the Tenant lock and the one-FUTURE constraint leave one rebuild.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildForChunkConvention() {
        try {
            var operating = tenants.operatingTenant();
            if (operating.isEmpty()) return;
            UUID tenant = operating.orElseThrow().value();
            var present = settings.present(tenant);
            if (present.isEmpty() || DocumentChunk.CONVENTION.equals(present.orElseThrow().chunkConvention())
                    || settings.future(tenant).isPresent()) return;
            var current = present.orElseThrow();
            UUID id = UUID.randomUUID();
            var generation = new SearchGeneration(id, tenant, current.providerId(), current.model(), current.dimensions(),
                    current.queryPrefix(), current.documentPrefix(), current.minimumSemanticScore(), DocumentChunk.CONVENTION,
                    SearchGeneration.identityFor(properties.indexPrefix(), id), SearchGeneration.Status.FUTURE, true,
                    clock.instant(), null, null);
            begin(generation, () -> { });
            LOGGER.atInfo().addKeyValue("event", "search.generation.rebuild_started").addKeyValue("generation", id)
                    .addKeyValue("identity", generation.identity()).addKeyValue("model", generation.model())
                    .addKeyValue("automatic", true).addKeyValue("previous_convention", current.chunkConvention())
                    .addKeyValue("release_convention", DocumentChunk.CONVENTION)
                    .log("The chunk convention changed; rebuilding the search index in the background");
        } catch (SearchSettingsException alreadyStarted) {
            LOGGER.atDebug().addKeyValue("event", "search.generation.rebuild_exists").log("Another process started the rebuild");
        } catch (RuntimeException failure) {
            LOGGER.atWarn().addKeyValue("event", "search.generation.automatic_rebuild_failed")
                    .addKeyValue("error_type", failure.getClass().getName())
                    .log("Could not start the rebuild for the new chunk convention; PRESENT keeps serving");
        }
    }

    /** Deletes the indexes of PAST generations whose retention ended; returns how many were removed. */
    public int cleanupExpired() {
        var operating = tenants.operatingTenant();
        if (operating.isEmpty()) return 0;
        int removed = 0;
        for (var generation : settings.expired(operating.orElseThrow().value(), clock.instant())) {
            if (cleanup(generation)) removed++;
        }
        return removed;
    }

    /**
     * Deletes the index, recounts it, and only then removes the generation. A failure is counted; from the third one
     * the generation is marked blocked, logged as an error and shown on the administration page, and still retried.
     */
    private boolean cleanup(@Nullable SearchGeneration generation) {
        if (generation == null || generation.status() != SearchGeneration.Status.PAST) return false;
        boolean gone;
        try { gone = index.deleteIndex(generation.identity()); }
        catch (RuntimeException failure) { gone = false; }
        if (gone) {
            transactions.executeWithoutResult(_ -> settings.deleteGeneration(generation));
            LOGGER.atInfo().addKeyValue("event", "search.generation.index_deleted").addKeyValue("generation", generation.id())
                    .addKeyValue("identity", generation.identity()).log("Deleted the index of a retired search generation");
            return true;
        }
        int attempts = Objects.requireNonNull(transactions.execute(_ -> settings.cleanupFailed(generation.tenantId(),
                generation.id(), CLEANUP_BLOCK_AFTER)));
        var event = attempts >= CLEANUP_BLOCK_AFTER ? LOGGER.atError() : LOGGER.atWarn();
        event.addKeyValue("event", attempts >= CLEANUP_BLOCK_AFTER ? "search.generation.cleanup_blocked" : "search.generation.cleanup_failed")
                .addKeyValue("generation", generation.id()).addKeyValue("identity", generation.identity())
                .addKeyValue("attempts", attempts).log("Could not delete the index of a retired search generation");
        return false;
    }

    // ---- helpers ----

    /** Model management of the operating Tenant; another Tenant's managers are refused. */
    private UUID authorize(ActorId actor, boolean write) {
        var access = write ? authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE)
                : authorization.require(actor, IamCapability.MODELS_MANAGE, false);
        var operating = tenants.operatingTenant().orElseThrow(SearchSettingsException::notPermitted);
        if (!operating.equals(access.tenantId())) throw SearchSettingsException.notPermitted();
        return operating.value();
    }

    private static void validate(GenerationInput input) {
        if (input.providerId() == null) throw SearchSettingsException.invalid("The provider is required.");
        if (input.model() == null || input.model().isBlank() || input.model().length() > 200 || !input.model().equals(input.model().trim())) {
            throw SearchSettingsException.invalid("The model name is required.");
        }
        if (input.dimensions() < 1 || input.dimensions() > 16000) throw SearchSettingsException.invalid("Dimensions must be between 1 and 16000.");
        if (input.queryPrefix() == null || input.documentPrefix() == null
                || input.queryPrefix().length() > 1000 || input.documentPrefix().length() > 1000) {
            throw SearchSettingsException.invalid("Prefixes are at most 1000 characters.");
        }
        if (!Double.isFinite(input.minimumSemanticScore()) || input.minimumSemanticScore() < 0 || input.minimumSemanticScore() > 1) {
            throw SearchSettingsException.invalid("The semantic score threshold must be between 0 and 1.");
        }
    }

    private static String name(@Nullable String name) {
        if (name == null || name.isBlank() || name.trim().length() > 200) throw SearchSettingsException.invalid("The name is required.");
        return name.trim();
    }

    private static String endpoint(String endpoint) {
        String trimmed = endpoint.trim();
        try { EmbeddingProvider.validateEndpoint(trimmed); }
        catch (IllegalArgumentException invalid) {
            throw SearchSettingsException.invalid("The endpoint must be an http or https URL without credentials, query or fragment.");
        }
        return trimmed;
    }

    private static EmbeddingProvider provider(UUID id, UUID tenant, String name, @Nullable String endpoint,
            @Nullable String credential, EmbeddingProvider.@Nullable DataBoundary boundary, long revision) {
        if (endpoint == null) throw SearchSettingsException.invalid("The endpoint is required.");
        if (boundary == null) throw SearchSettingsException.invalid("The data boundary is required.");
        return new EmbeddingProvider(id, tenant, name, endpoint(endpoint), credential, boundary, revision);
    }

    private String storedKey(EmbeddingProvider provider) {
        if (provider.credential() == null) return OpenAiCompatibleEmbeddings.NO_KEY;
        return credentials.resolve(provider.tenantId(), provider.id(), provider.credential());
    }

    private static String keyOrNone(@Nullable String key) {
        if (key != null && key.length() > 8192) throw SearchSettingsException.invalid("The API key is too long.");
        return key == null || key.isBlank() ? OpenAiCompatibleEmbeddings.NO_KEY : key;
    }
}
