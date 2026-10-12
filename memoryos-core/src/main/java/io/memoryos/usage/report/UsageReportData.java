package io.memoryos.usage.report;

import io.memoryos.usage.persistence.UsageReportRepository.ExportRow;
import io.memoryos.usage.persistence.UsageReportRepository.Member;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * The figures behind the report's PDF, aggregated from exactly the rows written to {@code usage_by_user.csv} so the
 * two reconcile (Onyx {@code build_usage_report_data}). Seats are the Tenant's active members.
 */
public record UsageReportData(
        String tenantName, LocalDate from, LocalDate to,
        BigDecimal totalCost, long calls, long unknownCostCalls,
        long inputTokens, long outputTokens, long cacheReadTokens,
        int members, int activePeople, int activeMembers, List<String> idleMembers,
        List<NamedSpend> topPeople, List<NamedSpend> byModel, List<NamedSpend> byFlow, List<NamedSpend> byGroup,
        List<NamedSpend> byBoundary, List<DailySpend> daily) {

    public static final int TOP_PEOPLE = 10;
    public static final int TOP_ENTRIES = 8;
    public static final int IDLE_SHOWN = 25;
    /** Row keys the renderer names in the reader's language; everything else is data. */
    public static final String SYSTEM = "SYSTEM";
    public static final String NO_BOUNDARY = "NONE";

    public record NamedSpend(String name, BigDecimal cost, long inputTokens, long outputTokens, int folded) {
        public long totalTokens() { return inputTokens + outputTokens; }
        /** "Other (n)": {@code folded} rows beyond the top of the list. */
        public boolean isOther() { return folded > 0; }
    }

    public record DailySpend(LocalDate day, BigDecimal cost, int activePeople) {}

    public boolean hasUsage() { return !daily.isEmpty(); }

    public BigDecimal costPerActivePerson() {
        return activePeople == 0 ? BigDecimal.ZERO : totalCost.divide(BigDecimal.valueOf(activePeople), 8, RoundingMode.HALF_UP);
    }

    public static Builder builder(String tenantName, LocalDate from, LocalDate to) { return new Builder(tenantName, from, to); }

    public static final class Builder {
        private final String tenantName;
        private final LocalDate from;
        private final LocalDate to;
        private final Map<String, Spend> people = new LinkedHashMap<>();
        private final Map<String, Spend> models = new LinkedHashMap<>();
        private final Map<String, Spend> flows = new LinkedHashMap<>();
        private final Map<String, Spend> groups = new LinkedHashMap<>();
        private final Map<String, Spend> boundaries = new LinkedHashMap<>();
        private final Map<LocalDate, BigDecimal> dailyCost = new TreeMap<>();
        private final Map<LocalDate, Set<UUID>> dailyPeople = new TreeMap<>();
        private final Set<UUID> active = new HashSet<>();
        private BigDecimal total = BigDecimal.ZERO;
        private long calls, unknown, input, output, cacheRead;

        private Builder(String tenantName, LocalDate from, LocalDate to) {
            this.tenantName = tenantName; this.from = from; this.to = to;
        }

        public Builder add(ExportRow row) {
            // Keyed by id: two people may share a display name.
            String person = row.actor() == null ? SYSTEM : row.actor().toString();
            add(people, person, row.actor() == null ? SYSTEM : label(row.name(), row.email(), person), row);
            add(models, row.model(), row.model(), row);
            add(flows, row.flow(), row.flow(), row);
            String boundary = row.boundary() == null ? NO_BOUNDARY : row.boundary();
            add(boundaries, boundary, boundary, row);
            // A person counts in every Group they belong to, so Group totals may exceed the Tenant total.
            for (String group : row.groups()) add(groups, group, group, row);
            total = total.add(row.cost());
            calls += row.calls(); unknown += row.unknownCostCalls();
            input += row.inputTokens(); output += row.outputTokens(); cacheRead += row.cacheReadTokens();
            dailyCost.merge(row.day(), row.cost(), BigDecimal::add);
            var today = dailyPeople.computeIfAbsent(row.day(), ignored -> new HashSet<>());
            // System work spends, but is not a person.
            if (row.actor() != null) { active.add(row.actor()); today.add(row.actor()); }
            return this;
        }

        public UsageReportData build(List<Member> members) {
            var seats = members.stream().filter(member -> "ACTIVE".equals(member.status())).toList();
            var seated = seats.stream().map(Member::actor).filter(active::contains).count();
            var idle = seats.stream().filter(member -> !active.contains(member.actor()))
                    .map(member -> label(member.name(), member.email(), member.actor().toString()))
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
            var daily = dailyCost.entrySet().stream()
                    .map(entry -> new DailySpend(entry.getKey(), entry.getValue(), dailyPeople.get(entry.getKey()).size()))
                    .toList();
            return new UsageReportData(tenantName, from, to, total, calls, unknown, input, output, cacheRead,
                    seats.size(), active.size(), (int) seated, idle,
                    top(people, TOP_PEOPLE), top(models, TOP_ENTRIES), top(flows, TOP_ENTRIES), top(groups, TOP_ENTRIES),
                    top(boundaries, Integer.MAX_VALUE), daily);
        }

        private static void add(Map<String, Spend> bucket, String key, String name, ExportRow row) {
            bucket.computeIfAbsent(key, ignored -> new Spend(name)).add(row);
        }
    }

    /** A person as the AI costs page names them: display name, then e-mail, then their id. */
    static String label(String name, String email, String id) {
        if (name != null && !name.isBlank()) return name;
        if (email != null && !email.isBlank()) return email;
        return id;
    }

    /** Highest cost first; beyond {@code limit} the rest fold into one row, but a single leftover keeps its name. */
    static List<NamedSpend> top(Map<String, Spend> bucket, int limit) {
        var ordered = bucket.values().stream()
                .sorted(Comparator.comparing((Spend spend) -> spend.cost).reversed().thenComparing(spend -> spend.name))
                .map(Spend::named).toList();
        if (ordered.size() <= limit || ordered.size() - limit == 1) return ordered;
        var head = new ArrayList<>(ordered.subList(0, limit));
        var tail = ordered.subList(limit, ordered.size());
        head.add(new NamedSpend("", sum(tail, NamedSpend::cost), tail.stream().mapToLong(NamedSpend::inputTokens).sum(),
                tail.stream().mapToLong(NamedSpend::outputTokens).sum(), tail.size()));
        return List.copyOf(head);
    }

    private static BigDecimal sum(List<NamedSpend> rows, Function<NamedSpend, BigDecimal> value) {
        return rows.stream().map(value).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static final class Spend {
        final String name;
        BigDecimal cost = BigDecimal.ZERO;
        long input, output;

        Spend(String name) { this.name = name; }

        void add(ExportRow row) { cost = cost.add(row.cost()); input += row.inputTokens(); output += row.outputTokens(); }

        NamedSpend named() { return new NamedSpend(name, cost, input, output, 0); }
    }
}
