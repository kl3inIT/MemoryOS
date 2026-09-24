package io.memoryos.retrieval.settings;

import io.memoryos.document.DocumentChunk;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.retrieval.embedding.OpenAiCompatibleEmbeddings;
import io.memoryos.retrieval.embedding.ValidatedEmbeddingService;
import io.memoryos.retrieval.opensearch.SearchProperties;
import io.memoryos.retrieval.settings.persistence.JdbcSearchSettingsRepository;
import io.memoryos.usage.AiUsageRecorder;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The active search generations of this process: PRESENT, which every search reads and every change is written to,
 * and the FUTURE being rebuilt beside it, each with the embedding client built for it.
 *
 * <p>The first call seeds PRESENT from deployment configuration when the operating Tenant has none, with the index
 * name the deployment already uses, so an existing index is kept rather than rebuilt. From then on deployment
 * configuration no longer decides the model. Until the operating Tenant is published (a worker can start before the
 * api bootstraps it) the configuration-derived generation is served unsaved and seeding is retried, which cannot
 * diverge because seeding writes exactly that generation.
 *
 * <p>Another process switches, restores, starts or cancels a rebuild, or edits a provider. Each process therefore
 * rereads a short version of the active generations at most every {@link #REFRESH} and reloads when it changed; a
 * change made in this process calls {@link #invalidate()} and is seen at once.
 */
@Service
public class SearchGenerations {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchGenerations.class);
    private static final Duration UNSEEDED_RETRY = Duration.ofSeconds(30);
    static final Duration REFRESH = Duration.ofSeconds(5);
    static final String SEEDED_PROVIDER_NAME = "Deployment";

    /** A generation with the client that embeds for it. */
    public record Active(SearchGeneration generation, ValidatedEmbeddingService embeddings, boolean persisted) {
        public String identity() { return generation.identity(); }
    }

    /** What this process serves: PRESENT, the FUTURE if one is being rebuilt, and the version they were read at. */
    private record Snapshot(Active present, @Nullable Active future, String version, long checkedAt,
            Map<UUID, Long> providerRevisions) { }

    private final @Nullable JdbcSearchSettingsRepository settings;
    private final @Nullable TenantAccessResolver tenants;
    private final @Nullable EmbeddingProviderCredentials credentials;
    private final @Nullable TransactionTemplate transaction;
    private final @Nullable ObservationRegistry observations;
    private final @Nullable ObjectProvider<AiUsageRecorder> usage;
    private final @Nullable Double inputPrice;
    private final SearchProperties properties;
    /** One concurrency bound per provider, shared by PRESENT and FUTURE when both use it. */
    private final Map<UUID, Semaphore> permits = new ConcurrentHashMap<>();
    private volatile @Nullable Snapshot current;
    private volatile long retryAt;
    private volatile Duration refresh = REFRESH;

    @Autowired
    public SearchGenerations(JdbcSearchSettingsRepository settings, TenantAccessResolver tenants,
            EmbeddingProviderCredentials credentials, SearchProperties properties, PlatformTransactionManager transactions,
            ObservationRegistry observations, ObjectProvider<AiUsageRecorder> usage,
            @Value("${memoryos.search.embedding-input-price-per-million:}") String price) {
        this.settings = settings;
        this.tenants = tenants;
        this.credentials = credentials;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactions);
        this.observations = observations;
        this.usage = usage;
        this.inputPrice = price.isBlank() ? null : Double.valueOf(price);
    }

    private SearchGenerations(Active fixed, SearchProperties properties) {
        this.settings = null;
        this.tenants = null;
        this.credentials = null;
        this.transaction = null;
        this.observations = null;
        this.usage = null;
        this.inputPrice = null;
        this.properties = properties;
        this.current = new Snapshot(fixed, null, "", Long.MAX_VALUE, Map.of());
    }

    /** A generation that never changes, for tests of index and search behavior. */
    public static SearchGenerations fixed(SearchGeneration generation, ValidatedEmbeddingService embeddings,
                                          SearchProperties properties) {
        return new SearchGenerations(new Active(generation, embeddings, true), properties);
    }

    /** The generation searches read and new chunks are written to. */
    public Active present() { return snapshot().present(); }

    /** The generation being rebuilt beside PRESENT, if any. */
    public Optional<Active> future() { return Optional.ofNullable(snapshot().future()); }

    /** The active generation owning the index, if the index is PRESENT or FUTURE. */
    public Optional<Active> active(String identity) {
        var snapshot = snapshot();
        if (snapshot.present().identity().equals(identity)) return Optional.of(snapshot.present());
        var future = snapshot.future();
        return future != null && future.identity().equals(identity) ? Optional.of(future) : Optional.empty();
    }

    /** The indexes changes are written to: PRESENT first, then the FUTURE being rebuilt. */
    public List<String> identities() {
        var snapshot = snapshot();
        var future = snapshot.future();
        return future == null ? List.of(snapshot.present().identity()) : List.of(snapshot.present().identity(), future.identity());
    }

    /** A client for a generation that is not active yet, such as the FUTURE about to be created. */
    public Active client(SearchGeneration generation, EmbeddingProvider provider) {
        var key = provider.credential() == null ? OpenAiCompatibleEmbeddings.NO_KEY
                : Objects.requireNonNull(credentials).resolve(provider.tenantId(), provider.id(), provider.credential());
        return new Active(generation, embeddings(generation, provider.id(), provider.endpoint(), key), true);
    }

    /** Tests shorten the refresh to observe another process's change without waiting. */
    void refreshEvery(Duration interval) { this.refresh = interval; }

    /** The next call rereads the generations; used after this process changed them. */
    public void invalidate() {
        var snapshot = current;
        if (snapshot != null && settings != null) current = new Snapshot(snapshot.present(), snapshot.future(), "", 0, snapshot.providerRevisions());
    }

    private Snapshot snapshot() {
        var snapshot = current;
        if (fresh(snapshot)) return Objects.requireNonNull(snapshot);
        synchronized (this) {
            snapshot = current;
            if (fresh(snapshot)) return Objects.requireNonNull(snapshot);
            if (snapshot != null && snapshot.present().persisted()) {
                var operating = Objects.requireNonNull(tenants).operatingTenant();
                if (operating.isPresent() && Objects.requireNonNull(settings).version(operating.orElseThrow().value())
                        .equals(snapshot.version())) {
                    snapshot = new Snapshot(snapshot.present(), snapshot.future(), snapshot.version(), System.nanoTime(),
                            snapshot.providerRevisions());
                    current = snapshot;
                    return snapshot;
                }
            }
            snapshot = load(snapshot);
            retryAt = System.nanoTime() + UNSEEDED_RETRY.toNanos();
            current = snapshot;
            return snapshot;
        }
    }

    private boolean fresh(@Nullable Snapshot snapshot) {
        if (snapshot == null) return false;
        if (!snapshot.present().persisted()) return System.nanoTime() - retryAt < 0;
        return System.nanoTime() - snapshot.checkedAt() < refresh.toNanos();
    }

    private Snapshot load(@Nullable Snapshot previous) {
        var repository = Objects.requireNonNull(settings);
        var tenant = Objects.requireNonNull(tenants).operatingTenant();
        if (tenant.isEmpty()) {
            LOGGER.atWarn().addKeyValue("event", "search.generation.unseeded")
                    .addKeyValue("identity", legacyIdentity(properties))
                    .log("No operating Tenant yet; serving the deployment-configured search generation unsaved");
            var unsaved = new UUID(0, 0);
            var generation = configured(properties, UUID.randomUUID(), unsaved, unsaved, Instant.now());
            return new Snapshot(new Active(generation, embeddings(generation, unsaved, properties.embeddingEndpoint(),
                    properties.apiKey()), false), null, "", System.nanoTime(), Map.of());
        }
        UUID operating = tenant.orElseThrow().value();
        record Read(SearchGeneration present, @Nullable SearchGeneration future, String version) { }
        var read = Objects.requireNonNull(Objects.requireNonNull(transaction).execute(_ -> {
            var present = repository.present(operating).orElseGet(() -> seed(repository, operating));
            return new Read(present, repository.future(operating).orElse(null), repository.version(operating));
        }));
        var generations = new ArrayList<@Nullable Active>();
        var revisions = new HashMap<UUID, Long>();
        for (var generation : new @Nullable SearchGeneration[] {read.present(), read.future()}) {
            if (generation == null) { generations.add(null); continue; }
            var provider = repository.provider(operating, generation.providerId())
                    .orElseThrow(() -> new IllegalStateException("search generation without provider"));
            revisions.put(generation.id(), provider.revision());
            var reused = reusable(previous, generation, provider);
            generations.add(reused != null ? reused : client(generation, provider));
        }
        return new Snapshot(Objects.requireNonNull(generations.get(0)), generations.get(1), read.version(), System.nanoTime(),
                Map.copyOf(revisions));
    }

    /** Keeps a client whose generation and provider did not change, and with it any in-flight embedding calls. */
    private static @Nullable Active reusable(@Nullable Snapshot previous, SearchGeneration generation, EmbeddingProvider provider) {
        if (previous == null) return null;
        for (var candidate : new Active[] {previous.present(), previous.future()}) {
            if (candidate != null && candidate.persisted() && candidate.generation().equals(generation)
                    && Long.valueOf(provider.revision()).equals(previous.providerRevisions().get(generation.id()))) return candidate;
        }
        return null;
    }

    private SearchGeneration seed(JdbcSearchSettingsRepository settings, UUID tenant) {
        settings.lock(tenant);
        var existing = settings.present(tenant);
        if (existing.isPresent()) return existing.orElseThrow();
        var provider = new EmbeddingProvider(UUID.randomUUID(), tenant, SEEDED_PROVIDER_NAME,
                properties.embeddingEndpoint(), EmbeddingProviderCredentials.DEPLOYMENT,
                EmbeddingProvider.DataBoundary.EXTERNAL, 1);
        settings.insertProvider(provider);
        var generation = configured(properties, UUID.randomUUID(), tenant, provider.id(), Instant.now());
        settings.insertGeneration(generation);
        LOGGER.atInfo().addKeyValue("event", "search.generation.seeded").addKeyValue("tenant", tenant)
                .addKeyValue("generation", generation.id()).addKeyValue("identity", generation.identity())
                .addKeyValue("model", generation.model()).addKeyValue("dimensions", generation.dimensions())
                .log("Seeded the PRESENT search generation from deployment configuration");
        return generation;
    }

    /** The generation deployment configuration describes, named like the index that configuration already uses. */
    static SearchGeneration configured(SearchProperties properties, UUID id, UUID tenant, UUID provider, Instant at) {
        return new SearchGeneration(id, tenant, provider, properties.model(), properties.dimensions(),
                properties.queryPrefix(), properties.documentPrefix(), properties.minimumSemanticScore(),
                DocumentChunk.CONVENTION, legacyIdentity(properties), SearchGeneration.Status.PRESENT, false, at, at, null);
    }

    /**
     * The index name deployments used before generations existed: a hash of endpoint, model, dimensions and chunk
     * convention. Only the seeded generation carries it; later generations are named by their own ID.
     */
    public static String legacyIdentity(SearchProperties properties) {
        try {
            String profile = properties.embeddingEndpoint() + ":" + properties.model() + ":" + properties.dimensions()
                    + ":" + DocumentChunk.CONVENTION;
            return properties.indexPrefix() + "-" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(profile.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private ValidatedEmbeddingService embeddings(SearchGeneration generation, UUID provider, String endpoint, String key) {
        var model = OpenAiCompatibleEmbeddings.model(endpoint, key, generation.model(), generation.dimensions(),
                properties.embeddingRetries(), properties.embeddingTimeout(), Objects.requireNonNull(observations));
        var recorder = Objects.requireNonNull(usage).getIfAvailable();
        return new ValidatedEmbeddingService(model, generation.model(), generation.dimensions(),
                properties.embeddingBatchSize(), properties.embeddingConcurrency(), recorder, URI.create(endpoint).getHost(),
                inputPrice, generation.queryPrefix(), generation.documentPrefix(),
                permits.computeIfAbsent(provider, _ -> new Semaphore(properties.embeddingConcurrency())));
    }
}
