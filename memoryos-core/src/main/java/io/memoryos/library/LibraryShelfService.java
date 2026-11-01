package io.memoryos.library;

import io.memoryos.connector.SourceSearchService;
import io.memoryos.iam.ActorProfileReader;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.library.persistence.JdbcLibraryMarkRepository;
import io.memoryos.library.persistence.JdbcLibraryRepository;
import io.memoryos.library.persistence.JdbcUserFileRepository;
import io.memoryos.retrieval.DocumentShelfService;
import io.memoryos.retrieval.ShelfDocument;
import io.memoryos.retrieval.ShelfQuery;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The library's views beside the owned listing (library hub): what is shared with the viewer, every meeting they
 * may read, the Source documents under their authority, what they opened lately and what they starred. Each
 * reachable row is authorized at every read by the capability that owns it, through {@link MeetingShelf},
 * {@link FileAttachments} and {@link DocumentShelfService}; the library copies nothing and counts none of it toward
 * the viewer's storage. The viewer's own marks are the only thing stored here, and a mark is never authority.
 */
@Service
public class LibraryShelfService {
    /** The meetings a person may read, as the meetings list bounds them. */
    static final int MAX_MEETINGS = 200;
    static final int MAX_AGENT_FILES = 1000;
    static final int MAX_OWNED_STARS = 1000;
    static final int MAX_REACHABLE_STARS = 500;
    /** Opens kept per person; Recent shows at most {@link #MAX_RECENT} of them. */
    static final int KEPT_OPENS = 200;
    static final int MAX_RECENT = 100;

    private static final Set<LibraryEntry.Kind> SHARED_KINDS =
            EnumSet.of(LibraryEntry.Kind.MEETING, LibraryEntry.Kind.AGENT_FILE);

    private final TenantAccessResolver tenants;
    private final JdbcLibraryRepository library;
    private final JdbcUserFileRepository files;
    private final JdbcLibraryMarkRepository marks;
    private final FileAttachments attachments;
    private final MeetingShelf meetings;
    private final DocumentShelfService documents;
    private final LibraryService owned;
    private final ActorProfileReader profiles;
    private final TransactionTemplate tx;

    public LibraryShelfService(TenantAccessResolver tenants, JdbcLibraryRepository library, JdbcUserFileRepository files,
            JdbcLibraryMarkRepository marks, FileAttachments attachments, MeetingShelf meetings,
            DocumentShelfService documents, LibraryService owned, ActorProfileReader profiles,
            PlatformTransactionManager transactionManager) {
        this.tenants = tenants; this.library = library; this.files = files; this.marks = marks;
        this.attachments = attachments; this.meetings = meetings; this.documents = documents; this.owned = owned;
        this.profiles = profiles;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public enum Sort { NEWEST, OLDEST, NAME }

    public record Page(List<LibraryEntry> items, long totalCount, boolean hasMore) {
        public Page { items = List.copyOf(items); }
    }

    public record DocumentPage(List<LibraryEntry> items, @Nullable String nextCursor) {
        public DocumentPage { items = List.copyOf(items); }
    }

    /** Meetings shared with the viewer and files of agents they use; {@code kinds} empty means both. */
    public record SharedQuery(String query, Set<LibraryEntry.Kind> kinds, Set<LibraryFile.Category> categories,
                              Sort sort, int offset, int limit) {
        public SharedQuery { kinds = Set.copyOf(kinds); categories = Set.copyOf(categories); }
    }

    /** Owned favourites and starred reachable rows; {@code kinds} empty means every kind. */
    public record StarredQuery(String query, Set<LibraryEntry.Kind> kinds, int offset, int limit) {
        public StarredQuery { kinds = Set.copyOf(kinds); }
    }

    @Transactional(readOnly = true)
    public Page shared(ActorId actor, SharedQuery query) {
        String text = text(query.query());
        Paging.check(query.offset(), query.limit());
        if (!SHARED_KINDS.containsAll(query.kinds())) throw LibraryException.invalid("Unknown kind.");
        var tenant = tenant(actor);
        var kinds = query.kinds().isEmpty() ? SHARED_KINDS : query.kinds();
        var rows = new ArrayList<LibraryEntry>();
        var owners = new HashMap<UUID, UUID>();
        if (kinds.contains(LibraryEntry.Kind.MEETING))
            meetings.readable(tenant, actor, MAX_MEETINGS).stream().filter(meeting -> !meeting.owned())
                    .map(LibraryShelfService::entry).forEach(rows::add);
        if (kinds.contains(LibraryEntry.Kind.AGENT_FILE))
            rows.addAll(agentEntries(tenant, actor, attachments.agentFiles(tenant, actor, MAX_AGENT_FILES), owners));
        var categories = query.categories();
        var matching = rows.stream().filter(entry -> matches(entry, text))
                .filter(entry -> categories.isEmpty() || entry.category() != null && categories.contains(entry.category()))
                .sorted(order(query.sort())).toList();
        return page(tenant, actor, matching, query.offset(), query.limit(), owners);
    }

    /** A page of the Source documents the viewer may read; requires Search, as Search itself does. */
    @Transactional(readOnly = true)
    public DocumentPage documents(ActorId actor, ShelfQuery query) {
        var tenant = tenant(actor);
        var page = documents.page(actor, query);
        return new DocumentPage(marked(tenant, actor,
                page.items().stream().map(LibraryShelfService::entry).toList()), page.nextCursor());
    }

    /** The Sources the viewer may narrow the documents view to. */
    public List<SourceSearchService.SourceOption> documentSources(ActorId actor) {
        return documents.sources(actor);
    }

    /** What the viewer opened lately, newest first, that they may still read. */
    @Transactional(readOnly = true)
    public List<LibraryEntry> recent(ActorId actor, int limit) {
        if (limit < 1 || limit > MAX_RECENT) throw LibraryException.invalid("Invalid page.");
        var tenant = tenant(actor);
        var opens = marks.opened(tenant, actor, limit);
        var found = resolve(tenant, actor, opens.stream().map(mark -> new Key(mark.kind(), mark.itemId())).toList());
        var rows = opens.stream().map(mark -> found.get(new Key(mark.kind(), mark.itemId())))
                .filter(Objects::nonNull).toList();
        return marked(tenant, actor, rows);
    }

    /** The viewer's starred rows, owned and reachable, most recently starred first. */
    @Transactional(readOnly = true)
    public Page starred(ActorId actor, StarredQuery query) {
        String text = text(query.query());
        Paging.check(query.offset(), query.limit());
        var tenant = tenant(actor);
        var kinds = query.kinds().isEmpty() ? EnumSet.allOf(LibraryEntry.Kind.class) : query.kinds();
        var rows = new ArrayList<Starred>();
        if (kinds.stream().anyMatch(LibraryEntry.Kind::ownedFile))
            library.favorites(tenant, actor, MAX_OWNED_STARS).stream()
                    .filter(favorite -> kinds.contains(kind(favorite.file().source())))
                    .forEach(favorite -> rows.add(new Starred(entry(favorite.file()), favorite.favoritedAt())));
        var stars = marks.starred(tenant, actor, MAX_REACHABLE_STARS).stream()
                .filter(mark -> kinds.contains(mark.kind())).toList();
        var owners = new HashMap<UUID, UUID>();
        var found = resolve(tenant, actor, stars.stream().map(mark -> new Key(mark.kind(), mark.itemId())).toList(), owners);
        for (var mark : stars) {
            var entry = found.get(new Key(mark.kind(), mark.itemId()));
            if (entry != null && mark.starredAt() != null) rows.add(new Starred(entry, mark.starredAt()));
        }
        var matching = rows.stream().filter(row -> matches(row.entry(), text))
                .sorted(Comparator.comparing(Starred::at).reversed()
                        .thenComparing(row -> row.entry().kind()).thenComparing(row -> row.entry().id()))
                .map(Starred::entry).toList();
        return page(tenant, actor, matching, query.offset(), query.limit(), owners);
    }

    /**
     * Stars a row the viewer may read now. An owned file keeps its own favourite; a reachable row is starred for
     * this viewer alone, up to {@link #MAX_REACHABLE_STARS}.
     */
    public void star(ActorId actor, LibraryEntry.Kind kind, UUID id) {
        if (kind.ownedFile()) {
            owned.update(actor, LibraryFile.Source.valueOf(kind.name()), id, null, true);
            return;
        }
        var tenant = reachable(actor, kind, id);
        tx.executeWithoutResult(ignored -> {
            lock(actor, tenant);
            if (marks.isStarred(tenant, actor, kind, id)) return;
            if (marks.starredCount(tenant, actor) >= MAX_REACHABLE_STARS) {
                forgetUnreachableStars(tenant, actor);
                if (marks.starredCount(tenant, actor) >= MAX_REACHABLE_STARS)
                    throw LibraryException.invalid("Star at most " + MAX_REACHABLE_STARS + " shared items.");
            }
            marks.star(tenant, actor, kind, id);
        });
    }

    public void unstar(ActorId actor, LibraryEntry.Kind kind, UUID id) {
        if (kind.ownedFile()) {
            owned.update(actor, LibraryFile.Source.valueOf(kind.name()), id, null, false);
            return;
        }
        var tenant = reachable(actor, kind, id);
        tx.executeWithoutResult(ignored -> {
            lock(actor, tenant);
            marks.unstar(tenant, actor, kind, List.of(id));
        });
    }

    /** Records that the viewer opened a row they may read now; only their newest {@link #KEPT_OPENS} are kept. */
    public void opened(ActorId actor, LibraryEntry.Kind kind, UUID id) {
        var tenant = reachable(actor, kind, id);
        tx.executeWithoutResult(ignored -> {
            lock(actor, tenant);
            marks.open(tenant, actor, kind, id, KEPT_OPENS);
        });
    }

    /** The viewer's Tenant, after the row resolved for them; a row they cannot read now is unavailable. */
    private TenantId reachable(ActorId actor, LibraryEntry.Kind kind, UUID id) {
        var tenant = tenant(actor);
        var key = new Key(kind, id);
        if (!resolve(tenant, actor, List.of(key)).containsKey(key)) throw LibraryException.unavailable();
        return tenant;
    }

    /** Serializes the viewer's mark writes with membership revocation and with each other. */
    private void lock(ActorId actor, TenantId tenant) {
        var current = tenants.lockActiveMembership(actor).orElseThrow(LibraryException::unavailable).tenantId();
        if (!current.equals(tenant)) throw LibraryException.unavailable();
        marks.lock(tenant, actor);
    }

    /**
     * Takes back the stars on rows the viewer can no longer read, which no view shows and so cannot be unstarred
     * by hand. Only done when the bound is reached, so a share revoked for a moment keeps its star otherwise.
     */
    private void forgetUnreachableStars(TenantId tenant, ActorId actor) {
        var keys = marks.starred(tenant, actor, MAX_REACHABLE_STARS).stream()
                .map(mark -> new Key(mark.kind(), mark.itemId())).toList();
        var found = resolve(tenant, actor, keys);
        keys.stream().filter(key -> !found.containsKey(key))
                .collect(Collectors.groupingBy(Key::kind, Collectors.mapping(Key::id, Collectors.toList())))
                .forEach((kind, ids) -> marks.unstar(tenant, actor, kind, ids));
    }

    private Map<Key, LibraryEntry> resolve(TenantId tenant, ActorId actor, Collection<Key> keys) {
        return resolve(tenant, actor, keys, new HashMap<>());
    }

    /**
     * The rows among {@code keys} the viewer may read now, each through the capability that owns it; the others
     * are absent. {@code owners} receives the uploader of each agent file, named only on the page returned.
     */
    private Map<Key, LibraryEntry> resolve(TenantId tenant, ActorId actor, Collection<Key> keys, Map<UUID, UUID> owners) {
        Map<LibraryEntry.Kind, Set<UUID>> byKind = keys.stream().collect(Collectors.groupingBy(Key::kind,
                () -> new EnumMap<>(LibraryEntry.Kind.class), Collectors.mapping(Key::id, Collectors.toSet())));
        var found = new HashMap<Key, LibraryEntry>();
        var ownedKinds = byKind.keySet().stream().filter(LibraryEntry.Kind::ownedFile).toList();
        if (!ownedKinds.isEmpty()) {
            var ids = ownedKinds.stream().flatMap(kind -> byKind.get(kind).stream()).collect(Collectors.toSet());
            var page = library.page(tenant, actor, new JdbcLibraryRepository.Filter("",
                    ownedKinds.stream().map(Enum::name).collect(Collectors.toSet()), Set.of(), null, false, false, ids),
                    LibraryFile.Sort.NEWEST, 0, ids.size());
            for (var file : page.items()) {
                var entry = entry(file);
                if (byKind.getOrDefault(entry.kind(), Set.of()).contains(entry.id())) found.put(key(entry), entry);
            }
        }
        var meetingIds = byKind.getOrDefault(LibraryEntry.Kind.MEETING, Set.of());
        if (!meetingIds.isEmpty())
            meetings.find(tenant, actor, meetingIds).forEach(meeting -> put(found, entry(meeting)));
        var agentFileIds = byKind.getOrDefault(LibraryEntry.Kind.AGENT_FILE, Set.of());
        if (!agentFileIds.isEmpty())
            agentEntries(tenant, actor, attachments.agentFiles(tenant, actor, agentFileIds), owners)
                    .forEach(entry -> put(found, entry));
        var documentIds = byKind.getOrDefault(LibraryEntry.Kind.DOCUMENT, Set.of());
        if (!documentIds.isEmpty())
            documents.find(actor, documentIds).forEach(document -> put(found, entry(document)));
        return found;
    }

    /**
     * An agent's files as rows: the uploads an agent the viewer uses grants them that someone else owns and that
     * are READY, each naming every such agent.
     */
    private List<LibraryEntry> agentEntries(TenantId tenant, ActorId actor, List<FileAttachments.AgentFile> grants,
                                            Map<UUID, UUID> owners) {
        if (grants.isEmpty()) return List.of();
        var agents = new LinkedHashMap<UUID, List<LibraryEntry.Agent>>();
        for (var grant : grants)
            agents.computeIfAbsent(grant.fileId(), ignored -> new ArrayList<>())
                    .add(new LibraryEntry.Agent(grant.agentId(), grant.agentName()));
        return files.granted(tenant, actor, agents.keySet()).stream().map(file -> {
            owners.put(file.id(), file.ownerId());
            var through = agents.get(file.id());
            return new LibraryEntry(LibraryEntry.Kind.AGENT_FILE, file.id(), file.filename(), file.mediaType(),
                    file.sizeBytes(), file.category(), file.createdAt(), false, false, null, null,
                    new LibraryEntry.Reason(LibraryEntry.ReasonKind.AGENT,
                            through.stream().map(LibraryEntry.Agent::name).toList()),
                    null, null, null, null, through, null);
        }).toList();
    }

    /** One page of rows already filtered and sorted, with uploader names and the viewer's marks. */
    private Page page(TenantId tenant, ActorId actor, List<LibraryEntry> rows, int offset, int limit,
                      Map<UUID, UUID> owners) {
        var items = rows.subList(Math.min(offset, rows.size()), Math.min(offset + limit, rows.size()));
        return new Page(marked(tenant, actor, named(items, owners)), rows.size(), offset + items.size() < rows.size());
    }

    /**
     * Names the uploader of each agent file on a page. The name is what the identity provider last reported, read
     * through IAM's own profile reader once per uploader on the page rather than by joining IAM's tables here.
     */
    private List<LibraryEntry> named(List<LibraryEntry> items, Map<UUID, UUID> owners) {
        var names = new HashMap<UUID, @Nullable String>();
        return items.stream().map(entry -> {
            var owner = entry.kind() == LibraryEntry.Kind.AGENT_FILE ? owners.get(entry.id()) : null;
            if (owner == null) return entry;
            return entry.withOwnerName(names.computeIfAbsent(owner, id -> displayName(profiles.read(new ActorId(id)))));
        }).toList();
    }

    /** The viewer's marks on every row, in one read: a reachable row's star is theirs, an owned file keeps its own. */
    private List<LibraryEntry> marked(TenantId tenant, ActorId actor, List<LibraryEntry> items) {
        if (items.isEmpty()) return List.of();
        var byKey = marks.marks(tenant, actor, items.stream().map(LibraryEntry::id).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(mark -> new Key(mark.kind(), mark.itemId()), Function.identity()));
        return items.stream().map(entry -> {
            var mark = byKey.get(key(entry));
            boolean starred = entry.kind().ownedFile() ? entry.starred() : mark != null && mark.starredAt() != null;
            return entry.marked(starred, mark == null ? null : mark.openedAt());
        }).toList();
    }

    private static LibraryEntry entry(LibraryFile file) {
        return new LibraryEntry(kind(file.source()), file.id(), file.filename(), file.mediaType(), file.sizeBytes(),
                file.category(), file.createdAt(), true, file.favorite(), null, null,
                new LibraryEntry.Reason(LibraryEntry.ReasonKind.OWNER, List.of()), file.sessionId(), file.sessionTitle(),
                file.messageId(), null, List.of(), null);
    }

    /** A meeting shared by name says so; one shared only with the viewer's Groups names those Groups. */
    private static LibraryEntry entry(ShelfMeeting meeting) {
        var reason = meeting.owned() ? new LibraryEntry.Reason(LibraryEntry.ReasonKind.OWNER, List.of())
                : meeting.sharedWithActor() ? new LibraryEntry.Reason(LibraryEntry.ReasonKind.MEMBER_SHARE, List.of())
                : new LibraryEntry.Reason(LibraryEntry.ReasonKind.GROUP_SHARE, meeting.groups());
        String owner = meeting.owned() || meeting.ownerName().isBlank() ? null : meeting.ownerName();
        return new LibraryEntry(LibraryEntry.Kind.MEETING, meeting.id(), meeting.title(), null, null, null,
                meeting.createdAt(), meeting.owned(), false, null, owner, reason, null, null, null,
                new LibraryEntry.MeetingState(meeting.status(), meeting.minutesReady(), meeting.hasTranscript(),
                        meeting.durationMs()), List.of(), null);
    }

    private static LibraryEntry entry(ShelfDocument document) {
        var reason = switch (document.access()) {
            case PUBLIC -> new LibraryEntry.Reason(LibraryEntry.ReasonKind.PUBLIC_SOURCE, List.of());
            case GROUP -> new LibraryEntry.Reason(LibraryEntry.ReasonKind.GROUP_SOURCE, document.groups());
            case PROVIDER -> new LibraryEntry.Reason(LibraryEntry.ReasonKind.PROVIDER_SOURCE, List.of());
        };
        return new LibraryEntry(LibraryEntry.Kind.DOCUMENT, document.documentId(), document.filename(),
                document.mediaType(), document.sizeBytes(), LibraryFile.Category.valueOf(document.category()),
                document.updatedAt(), false, false, null, null, reason, null, null, null, null, List.of(),
                new LibraryEntry.DocumentLink(document.generation(), document.title(), document.sourceId(),
                        document.sourceName(), document.sourceType(), document.providerUrl()));
    }

    private static LibraryEntry.Kind kind(LibraryFile.Source source) {
        return LibraryEntry.Kind.valueOf(source.name());
    }

    private static @Nullable String displayName(ActorProfileReader.Profile profile) {
        if (profile.displayName() != null && !profile.displayName().isBlank()) return profile.displayName();
        return profile.email() == null || profile.email().isBlank() ? null : profile.email();
    }

    private static void put(Map<Key, LibraryEntry> found, LibraryEntry entry) {
        found.put(key(entry), entry);
    }

    private static Key key(LibraryEntry entry) { return new Key(entry.kind(), entry.id()); }

    private static boolean matches(LibraryEntry entry, String text) {
        return text.isEmpty() || entry.name().toLowerCase(Locale.ROOT).contains(text);
    }

    /** Search text as rows are matched against it: trimmed and lower-cased, at most 200 characters. */
    private static String text(String query) {
        if (query.length() > 200 || query.indexOf('\0') >= 0) throw LibraryException.invalid("Invalid search text.");
        return query.strip().toLowerCase(Locale.ROOT);
    }

    private static Comparator<LibraryEntry> order(Sort sort) {
        Comparator<LibraryEntry> tie = Comparator.comparing(LibraryEntry::kind).thenComparing(LibraryEntry::id);
        return switch (sort) {
            case NEWEST -> Comparator.comparing(LibraryEntry::at).reversed().thenComparing(tie);
            case OLDEST -> Comparator.comparing(LibraryEntry::at).thenComparing(tie);
            case NAME -> Comparator.comparing((LibraryEntry entry) -> entry.name().toLowerCase(Locale.ROOT))
                    .thenComparing(Comparator.comparing(LibraryEntry::at).reversed()).thenComparing(tie);
        };
    }

    private TenantId tenant(ActorId actor) {
        return tenants.findActiveTenant(actor).orElseThrow(LibraryException::unavailable);
    }

    private record Key(LibraryEntry.Kind kind, UUID id) {}

    private record Starred(LibraryEntry entry, Instant at) {}
}
