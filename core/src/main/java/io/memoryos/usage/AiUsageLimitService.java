package io.memoryos.usage;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import io.memoryos.usage.persistence.AiUsageLimitRepository;
import io.memoryos.usage.persistence.AiUsageLimitRepository.DaySpend;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a Tenant, a Group and a person may spend on AI, and the check a chat turn runs before its provider is called
 * (MEM-123). The check reads spend that has settled: turns still running are invisible to it, as in Onyx, so turns
 * started at the same moment can overshoot the cap together. The next turn is refused.
 */
@Service
public class AiUsageLimitService {
    /** As in Onyx, a Tenant with no limit must not pay for a query on every turn. */
    private static final long CACHE_MILLIS = 60_000;

    private final IamAuthorization authorization;
    private final AiUsageLimitRepository limits;
    private final AuditTrail audit;
    private final Clock clock;
    private final Map<UUID, Cached> configured = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public AiUsageLimitService(IamAuthorization authorization, AiUsageLimitRepository limits, AuditTrail audit) {
        this(authorization, limits, audit, Clock.systemUTC());
    }

    AiUsageLimitService(IamAuthorization authorization, AiUsageLimitRepository limits, AuditTrail audit, Clock clock) {
        this.authorization = authorization;
        this.limits = limits;
        this.audit = audit;
        this.clock = clock;
    }

    private record Cached(boolean any, long readAt) {}

    /** The limit a turn would exceed: which budget bound it, and when that budget frees again. */
    public record Breach(AiUsageLimitScope scope, @Nullable String groupName, Budget budget, Instant resetsAt) {
        public enum Budget { TOKENS, COST }
    }

    /** What a person has spent against the limit that binds them, for their own usage page. */
    public record Standing(AiUsageLimitScope scope, @Nullable String groupName, @Nullable Long tokenBudget,
                           long tokensUsed, @Nullable BigDecimal costBudgetUsd, BigDecimal costUsed,
                           int periodDays, Instant resetsAt) {}

    /**
     * The check a chat turn runs. Returns the binding limit when the turn must be refused, and nothing when the turn
     * is within every budget.
     */
    @Transactional(readOnly = true)
    public Optional<Breach> check(ActorId actor) {
        TenantId tenant = member(actor);
        if (!anyConfigured(tenant.value())) return Optional.empty();
        for (Weighed weighed : weigh(tenant.value(), actor.value())) {
            if (weighed.breach() != null) return Optional.of(weighed.breach());
        }
        return Optional.empty();
    }

    /** The check with its refusal: what a chat turn calls, so every caller refuses the same way. */
    public void enforce(ActorId actor) {
        check(actor).ifPresent(breach -> { throw new AiUsageLimitException(breach, message(breach)); });
    }

    private static String message(Breach breach) {
        String whose = switch (breach.scope()) {
            case TENANT -> "your organization";
            case GROUP -> breach.groupName() == null ? "your Group" : "the Group " + breach.groupName();
            case PERSON -> "you";
        };
        return "The AI budget for " + whose + " is spent. It frees again on "
                + java.time.format.DateTimeFormatter.ISO_INSTANT.format(breach.resetsAt()) + ".";
    }

    /** The budget that binds this person, for the banner on their own usage page; the tightest one is shown. */
    @Transactional(readOnly = true)
    public Optional<Standing> standing(ActorId actor) {
        TenantId tenant = member(actor);
        if (!anyConfigured(tenant.value())) return Optional.empty();
        return weigh(tenant.value(), actor.value()).stream()
                .min((left, right) -> Double.compare(right.share(), left.share()))
                .map(Weighed::standing);
    }

    private boolean anyConfigured(UUID tenant) {
        long now = clock.millis();
        Cached cached = configured.get(tenant);
        if (cached != null && now - cached.readAt() < CACHE_MILLIS) return cached.any();
        boolean any = limits.anyEnabled(tenant);
        configured.put(tenant, new Cached(any, now));
        return any;
    }

    private void forget(UUID tenant) {
        configured.remove(tenant);
    }

    private record Weighed(AiUsageLimit limit, long tokens, BigDecimal cost, @Nullable Breach breach, double share,
                           Instant resetsAt) {
        Standing standing() {
            return new Standing(limit.scope(), limit.groupName(), limit.tokenBudget(), tokens, limit.costBudgetUsd(),
                    cost, limit.periodDays(), resetsAt);
        }
    }

    /**
     * Every limit that applies to this person, with what has been spent against it. A Group limit applies only to the
     * Groups the person belongs to, and the tightest of them binds. Onyx lets a person escape a strict Group's cap
     * when any other Group of theirs is under budget; MemoryOS does not, because a person's spend already counts in
     * every Group they belong to.
     */
    private List<Weighed> weigh(UUID tenant, UUID actor) {
        List<AiUsageLimit> configuredLimits = limits.enabled(tenant);
        if (configuredLimits.isEmpty()) return List.of();
        List<UUID> groups = configuredLimits.stream().anyMatch(limit -> limit.scope() == AiUsageLimitScope.GROUP)
                ? limits.groupsOf(tenant, actor) : List.of();
        List<Weighed> weighed = new ArrayList<>();
        for (AiUsageLimit limit : configuredLimits) {
            UUID subject = switch (limit.scope()) {
                case TENANT -> null;
                case PERSON -> actor;
                case GROUP -> limit.groupId();
            };
            if (limit.scope() == AiUsageLimitScope.GROUP && !groups.contains(limit.groupId())) continue;
            LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
            LocalDate from = today.minusDays(limit.periodDays() - 1L);
            List<DaySpend> spend = limits.spend(tenant, from, limit.scope(), subject);
            long tokens = spend.stream().mapToLong(DaySpend::tokens).sum();
            BigDecimal cost = spend.stream().map(DaySpend::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
            weighed.add(measure(limit, spend, tokens, cost, today));
        }
        return weighed;
    }

    private Weighed measure(AiUsageLimit limit, List<DaySpend> spend, long tokens, BigDecimal cost, LocalDate today) {
        boolean overTokens = limit.tokenBudget() != null && tokens >= limit.tokenBudget();
        boolean overCost = limit.costBudgetUsd() != null && cost.compareTo(limit.costBudgetUsd()) >= 0;
        Instant resetsAt = overTokens
                ? freesAt(spend, today, limit, day -> BigDecimal.valueOf(day.tokens()), BigDecimal.valueOf(limit.tokenBudget()))
                : overCost ? freesAt(spend, today, limit, DaySpend::cost, limit.costBudgetUsd()) : nextDay(today);
        Breach breach = overTokens || overCost
                ? new Breach(limit.scope(), limit.groupName(), overTokens ? Breach.Budget.TOKENS : Breach.Budget.COST, resetsAt)
                : null;
        return new Weighed(limit, tokens, cost, breach, share(limit, tokens, cost), resetsAt);
    }

    private static double share(AiUsageLimit limit, long tokens, BigDecimal cost) {
        double byTokens = limit.tokenBudget() == null ? 0 : (double) tokens / limit.tokenBudget();
        double byCost = limit.costBudgetUsd() == null || limit.costBudgetUsd().signum() == 0 ? 0
                : cost.doubleValue() / limit.costBudgetUsd().doubleValue();
        return Math.max(byTokens, byCost);
    }

    /**
     * When the trailing window has dropped enough for the next turn to be admitted: the boundary at which the oldest
     * days leave the window and what remains is under budget. Waiting until then cannot immediately trip again.
     */
    private static Instant freesAt(List<DaySpend> spend, LocalDate today, AiUsageLimit limit,
                                   java.util.function.Function<DaySpend, BigDecimal> measure, BigDecimal budget) {
        BigDecimal remaining = spend.stream().map(measure).reduce(BigDecimal.ZERO, BigDecimal::add);
        for (DaySpend day : spend) {
            remaining = remaining.subtract(measure.apply(day));
            // The window keeps period_days days, so this day leaves it the morning after the last day that includes it.
            LocalDate leaves = day.day().plusDays(limit.periodDays());
            if (remaining.compareTo(budget) < 0) return leaves.atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return nextDay(today);
    }

    private static Instant nextDay(LocalDate today) {
        return today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    // Administration. Every limit is a model manager's decision about spending, and every change is recorded.

    /** A limit with what has been spent against it in its own window, so the screen shows both on one row. */
    public record Configured(AiUsageLimit limit, long tokensUsed, BigDecimal costUsed) {}

    @Transactional(readOnly = true)
    public List<Configured> list(ActorId manager) {
        UUID tenant = manage(manager).value();
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        return limits.list(tenant).stream().map(limit -> {
            LocalDate from = today.minusDays(limit.periodDays() - 1L);
            // A per-person budget has no single total; the busiest person is the one closest to it.
            if (limit.scope() == AiUsageLimitScope.PERSON) {
                var busiest = limits.busiestPerson(tenant, from);
                return new Configured(limit, busiest.tokens(), busiest.cost());
            }
            List<DaySpend> spend = limits.spend(tenant, from, limit.scope(), limit.groupId());
            return new Configured(limit, spend.stream().mapToLong(DaySpend::tokens).sum(),
                    spend.stream().map(DaySpend::cost).reduce(BigDecimal.ZERO, BigDecimal::add));
        }).toList();
    }

    @Transactional
    public AiUsageLimit create(ActorId manager, AiUsageLimit limit) {
        TenantId tenant = manage(manager);
        AiUsageLimit created = new AiUsageLimit(UUID.randomUUID(), limit.scope(), limit.groupId(), limit.groupName(),
                limit.tokenBudget(), limit.costBudgetUsd(), limit.periodDays(), limit.enabled());
        limits.insert(tenant.value(), created);
        forget(tenant.value());
        audit.record(record(AuditAction.AI_LIMIT_CREATE, tenant, manager, created).detail("after", budget(created)).build());
        return limits.find(tenant.value(), created.id()).orElseThrow();
    }

    @Transactional
    public AiUsageLimit update(ActorId manager, UUID id, AiUsageLimit changes) {
        TenantId tenant = manage(manager);
        AiUsageLimit before = limits.find(tenant.value(), id).orElseThrow(AiCostException::limitNotFound);
        if (changes.scope() != before.scope() || !java.util.Objects.equals(changes.groupId(), before.groupId()))
            throw AiCostException.invalid("A limit keeps who it applies to; remove it and set a new one instead.");
        AiUsageLimit after = new AiUsageLimit(id, before.scope(), before.groupId(), before.groupName(),
                changes.tokenBudget(), changes.costBudgetUsd(), changes.periodDays(), changes.enabled());
        limits.update(tenant.value(), after);
        forget(tenant.value());
        audit.record(record(AuditAction.AI_LIMIT_UPDATE, tenant, manager, after)
                .detail("before", budget(before)).detail("after", budget(after)).build());
        return limits.find(tenant.value(), id).orElseThrow();
    }

    @Transactional
    public void delete(ActorId manager, UUID id) {
        TenantId tenant = manage(manager);
        AiUsageLimit before = limits.find(tenant.value(), id).orElseThrow(AiCostException::limitNotFound);
        limits.delete(tenant.value(), id);
        forget(tenant.value());
        audit.record(record(AuditAction.AI_LIMIT_DELETE, tenant, manager, before).detail("before", budget(before)).build());
    }

    private AuditRecord.Builder record(AuditAction action, TenantId tenant, ActorId manager, AiUsageLimit limit) {
        return AuditRecord.of(action, tenant).actor(manager)
                .resource("AI_LIMIT", limit.id(), limit.groupName() == null ? limit.scope().name() : limit.groupName())
                .detail("scope", limit.scope().name()).detail("group", limit.groupName());
    }

    private static Map<String, Object> budget(AiUsageLimit limit) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tokenBudget", limit.tokenBudget());
        fields.put("costBudgetUsd", limit.costBudgetUsd());
        fields.put("periodDays", limit.periodDays());
        fields.put("enabled", limit.enabled());
        return fields;
    }

    /** Any member may be capped, so the check needs their Tenant, not an administrative capability. */
    private TenantId member(ActorId actor) {
        return authorization.require(actor, IamCapability.SYSTEM_BASIC, false).tenantId();
    }

    private TenantId manage(ActorId manager) {
        return authorization.require(manager, IamCapability.MODELS_MANAGE, false).tenantId();
    }
}
