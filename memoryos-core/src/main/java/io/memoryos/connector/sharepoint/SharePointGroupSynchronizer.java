package io.memoryos.connector.sharepoint;

import io.memoryos.connector.SharePointException;
import io.memoryos.connector.SharePointGateway;
import io.memoryos.connector.SharePointGateway.Principal;
import io.memoryos.connector.SharePointGateway.PrincipalType;
import io.memoryos.connector.SharePointProviderException;
import io.memoryos.connector.SharePointProviderException.Failure;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointGroupRepository;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointGroupRepository.Kind;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointGroupRepository.Run;
import io.memoryos.connector.sharepoint.persistence.JdbcSharePointGroupRepository.Task;
import io.memoryos.connector.sync.SyncRun;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Reads who the groups of a SYNC Source contain, as Onyx does (MEM-136): the role assignments of each site the Source
 * holds an item of name groups; each group is asked for its direct members; a group found among them is asked in
 * turn. One request is made for each step, outside any transaction, and applied inside a fence of the
 * synchronization run that drives it, so the read continues across slices and a superseded run writes nothing.
 *
 * <p>A group that no longer exists admits nobody. A site that cannot be asked ends the read without replacing the
 * membership in force, and Microsoft refusing to answer at all fails the run that drives it
 * ({@link SharePointException#permissionsDenied()}): the Entra application lacks what Auto Sync needs.
 */
@Component
public class SharePointGroupSynchronizer {
    private static final Logger LOGGER = LoggerFactory.getLogger(SharePointGroupSynchronizer.class);
    /** Where Onyx stops asking the directory for more groups. */
    private static final int MAX_DIRECTORY_GROUPS = 100_000;

    private final JdbcSharePointGroupRepository groups;
    private final Duration interval;
    private final boolean directory;

    public SharePointGroupSynchronizer(JdbcSharePointGroupRepository groups,
            @Value("${memoryos.sharepoint.group-sync-interval:PT5M}") Duration interval,
            @Value("${memoryos.sharepoint.enumerate-directory-groups:false}") boolean directory) {
        if (interval.isNegative() || interval.isZero()) throw new IllegalArgumentException("group sync interval must be positive");
        this.groups = groups;
        this.interval = interval;
        this.directory = directory;
    }

    /**
     * Advances the Source's group membership by one request. Returns {@code false} when nothing is due or the read
     * ended, so the caller can stop asking during this run.
     */
    public boolean advance(SyncRun run, SharePointGateway.Session session, String tenantHost) {
        var tenant = run.work().tenantId();
        var source = run.work().sourceId();
        var step = run.fenced(_ -> {
            var current = groups.running(tenant, source)
                    .or(() -> groups.due(tenant, source, interval)
                            ? Optional.of(groups.start(tenant, source, directory)) : Optional.empty())
                    .orElse(null);
            if (current == null) return null;
            var task = groups.next(current).orElse(null);
            if (task == null) groups.complete(current);
            return task == null ? null : new Step(current, task);
        });
        if (step.isEmpty()) return false;
        Run current = step.get().run();
        Task task = step.get().task();
        try {
            ask(run, current, task, session, tenantHost);
            return true;
        } catch (SharePointProviderException exception) {
            // The connection itself failing ends the slice; the read continues where it stood.
            if (exception.failure() == Failure.AUTHENTICATION || exception.failure() == Failure.QUOTA
                    || exception.failure() == Failure.UNAVAILABLE) throw exception;
            if (exception.failure() == Failure.AUTHORIZATION) {
                // The application may not read permissions at all: the run says so instead of ending quietly with
                // documents nobody can read.
                run.fenced(_ -> {
                    groups.fail(current, SharePointException.PERMISSIONS_DENIED);
                    return true;
                });
                throw SharePointException.permissionsDenied();
            }
            boolean group = task.kind() == Kind.SITE_GROUP || task.kind() == Kind.ENTRA_GROUP;
            if (group || exception.failure() == Failure.NOT_FOUND) {
                // A group since deleted, or a site that is gone: nobody is admitted through it.
                run.fenced(_ -> {
                    groups.advanced(current, task, null);
                    return true;
                });
                return true;
            }
            String code = "SOURCE_SHAREPOINT_" + exception.failure().name();
            run.fenced(_ -> {
                groups.fail(current, code);
                return true;
            });
            LOGGER.atWarn().addKeyValue("event", "sharepoint.group_sync.failed")
                    .addKeyValue("source_id", source.value()).addKeyValue("error_type", "provider")
                    .addKeyValue("error_code", code).log("SharePoint group membership was not read");
            return false;
        }
    }

    private void ask(SyncRun run, Run current, Task task, SharePointGateway.Session session, String tenantHost) {
        switch (task.kind()) {
            case WEB -> {
                var found = new ArrayList<Principal>();
                for (var assignment : session.webRoleAssignments(tenantHost, task.key())) {
                    if (!assignment.limitedAccessOnly()) found.add(assignment.member());
                }
                run.fenced(_ -> {
                    for (Principal principal : found) group(current, task.key(), principal);
                    groups.advanced(current, task, null);
                    return true;
                });
            }
            case SITE_GROUP -> {
                String web = Objects.requireNonNull(task.webUrl());
                var members = session.siteGroupMembers(tenantHost, web,
                        Long.parseLong(Objects.requireNonNull(task.providerRef())));
                run.fenced(_ -> {
                    groups.members(current, task.key(), emails(members.stream()
                            .filter(member -> member.type() == PrincipalType.USER)
                            .map(Principal::userPrincipalName).toList()));
                    for (Principal member : members) group(current, web, member);
                    groups.advanced(current, task, null);
                    return true;
                });
            }
            case ENTRA_GROUP -> {
                var page = session.entraGroupMembers(Objects.requireNonNull(task.providerRef()), task.pageLink());
                run.fenced(_ -> {
                    groups.members(current, task.key(), emails(page.members().stream()
                            .filter(member -> !member.group()).map(SharePointGateway.EntraMember::userPrincipalName)
                            .toList()));
                    for (var member : page.members()) {
                        if (member.group()) entraGroup(current, member.id(), member.displayName());
                    }
                    groups.advanced(current, task, page.nextLink());
                    return true;
                });
            }
            case DIRECTORY -> {
                var page = session.entraGroups(task.pageLink());
                run.fenced(_ -> {
                    for (var listed : page.groups()) entraGroup(current, listed.id(), listed.displayName());
                    groups.advanced(current, task,
                            groups.groups(current) >= MAX_DIRECTORY_GROUPS ? null : page.nextLink());
                    return true;
                });
            }
        }
    }

    /** Queues a group a site or a SharePoint group names; a person is not a group, and everyone is not listed. */
    private void group(Run current, String webUrl, Principal principal) {
        if (principal.type() == PrincipalType.SHAREPOINT_GROUP) {
            String groupId = SharePointAccess.siteGroupId(webUrl, principal);
            if (groupId != null) groups.group(current, Kind.SITE_GROUP, groupId, webUrl, Long.toString(principal.id()));
        } else if (principal.type() == PrincipalType.ENTRA_GROUP && !SharePointAccess.everyone(principal.loginName())) {
            String id = SharePointAccess.entraId(principal.loginName());
            if (id != null) entraGroup(current, id, principal.title());
        }
    }

    private void entraGroup(Run current, String id, @Nullable String displayName) {
        // The directory names a group by an identifier; anything else is not asked about.
        String entraId = SharePointAccess.entraId(id);
        String groupId = entraId == null ? null : SharePointAccess.entraGroupId(displayName, entraId);
        if (groupId != null) groups.group(current, Kind.ENTRA_GROUP, groupId, null, entraId);
    }

    private static List<String> emails(List<@Nullable String> userPrincipalNames) {
        var emails = new ArrayList<String>();
        for (String name : userPrincipalNames) {
            String email = SharePointAccess.email(name);
            if (email != null) emails.add(email);
        }
        return emails;
    }

    private record Step(Run run, Task task) {}
}
