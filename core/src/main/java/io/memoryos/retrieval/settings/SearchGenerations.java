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
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
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
 * The PRESENT search generation every search and indexing call uses, with the embedding client built for it.
 *
 * <p>The first call seeds PRESENT from deployment configuration when the operating Tenant has none, with the index
 * name the deployment already uses, so an existing index is kept rather than rebuilt. From then on deployment
 * configuration no longer decides the model. The generation is read once and cached; until the operating Tenant is
 * published (a worker can start before the api bootstraps it) the configuration-derived generation is served
 * unsaved and seeding is retried, which cannot diverge because seeding writes exactly that generation.
 */
@Service
public class SearchGenerations {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchGenerations.class);
    private static final Duration UNSEEDED_RETRY = Duration.ofSeconds(30);
    static final String SEEDED_PROVIDER_NAME = "Deployment";

    /** The generation searches read and new chunks are written to, with the client that embeds for it. */
    public record Active(SearchGeneration generation, ValidatedEmbeddingService embeddings, boolean persisted) {
        public String identity() { return generation.identity(); }
    }

    private final @Nullable Supplier<Active> loader;
    private final SearchProperties properties;
    private volatile @Nullable Active current;
    private volatile long retryAt;

    @Autowired
    public SearchGenerations(JdbcSearchSettingsRepository settings, TenantAccessResolver tenants,
            EmbeddingProviderCredentials credentials, SearchProperties properties, PlatformTransactionManager transactions,
            ObservationRegistry observations, ObjectProvider<AiUsageRecorder> usage,
            @Value("${memoryos.search.embedding-input-price-per-million:}") String price) {
        this.properties = properties;
        var transaction = new TransactionTemplate(transactions);
        @Nullable Double inputPrice = price.isBlank() ? null : Double.valueOf(price);
        this.loader = () -> {
            var tenant = tenants.operatingTenant();
            if (tenant.isEmpty()) {
                LOGGER.atWarn().addKeyValue("event", "search.generation.unseeded")
                        .addKeyValue("identity", legacyIdentity(properties))
                        .log("No operating Tenant yet; serving the deployment-configured search generation unsaved");
                var unsaved = new UUID(0, 0);
                var generation = configured(properties, UUID.randomUUID(), unsaved, unsaved, Instant.now());
                return new Active(generation, embeddings(generation, properties.embeddingEndpoint(), properties.apiKey(),
                        observations, usage.getIfAvailable(), inputPrice), false);
            }
            UUID operating = tenant.orElseThrow().value();
            var generation = Objects.requireNonNull(transaction.execute(_ -> settings.present(operating)
                    .orElseGet(() -> seed(settings, operating))));
            var provider = settings.provider(operating, generation.providerId())
                    .orElseThrow(() -> new IllegalStateException("search generation without provider"));
            String key = credentials.resolve(operating, provider.id(), provider.credential());
            if (!DocumentChunk.CONVENTION.equals(generation.chunkConvention())) {
                LOGGER.atWarn().addKeyValue("event", "search.generation.chunk_convention_outdated")
                        .addKeyValue("identity", generation.identity()).addKeyValue("generation", generation.id())
                        .addKeyValue("generation_convention", generation.chunkConvention())
                        .addKeyValue("release_convention", DocumentChunk.CONVENTION)
                        .log("The PRESENT search generation was built with another chunk convention");
            }
            return new Active(generation, embeddings(generation, provider.endpoint(), key, observations,
                    usage.getIfAvailable(), inputPrice), true);
        };
    }

    private SearchGenerations(Active fixed, SearchProperties properties) {
        this.loader = null;
        this.properties = properties;
        this.current = fixed;
    }

    /** A generation that never changes, for tests of index and search behavior. */
    public static SearchGenerations fixed(SearchGeneration generation, ValidatedEmbeddingService embeddings,
                                          SearchProperties properties) {
        return new SearchGenerations(new Active(generation, embeddings, true), properties);
    }

    public Active present() {
        var active = current;
        if (active != null && (active.persisted() || System.nanoTime() - retryAt < 0)) return active;
        synchronized (this) {
            active = current;
            if (active != null && (active.persisted() || System.nanoTime() - retryAt < 0)) return active;
            active = Objects.requireNonNull(loader).get();
            retryAt = System.nanoTime() + UNSEEDED_RETRY.toNanos();
            current = active;
            return active;
        }
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

    private ValidatedEmbeddingService embeddings(SearchGeneration generation, String endpoint, String key,
            ObservationRegistry observations, @Nullable AiUsageRecorder usage, @Nullable Double inputPrice) {
        var model = OpenAiCompatibleEmbeddings.model(endpoint, key, generation.model(), generation.dimensions(),
                properties.embeddingRetries(), properties.embeddingTimeout(), observations);
        return new ValidatedEmbeddingService(model, generation.model(), generation.dimensions(),
                properties.embeddingBatchSize(), properties.embeddingConcurrency(), usage, URI.create(endpoint).getHost(),
                inputPrice, generation.queryPrefix(), generation.documentPrefix());
    }
}
