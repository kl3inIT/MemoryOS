package io.memoryos.chat;

import io.memoryos.chat.persistence.JdbcUserFileRepository;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;
import io.memoryos.retrieval.DocumentSearchService;
import io.memoryos.retrieval.SearchHit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ChatFileSearchService {
    private final JdbcUserFileRepository files;
    private final TenantAccessResolver tenants;
    private final DocumentSearchService search;
    public ChatFileSearchService(JdbcUserFileRepository files, TenantAccessResolver tenants, DocumentSearchService search) {
        this.files = files; this.tenants = tenants; this.search = search;
    }
    public record FileHit(UUID fileId, SearchHit passage) {}
    public Set<UUID> ready(ActorId actor, Set<UUID> allowed) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        var scope = files.documents(tenant, actor, allowed);
        var readyDocuments = search.readyFiles(actor, tenant, scope);
        authorize(actor, tenant);
        return files.documents(tenant, actor, allowed).entrySet().stream()
                .filter(entry -> readyDocuments.contains(entry.getValue())).map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public List<FileHit> search(ActorId actor, TenantId tenant, Set<UUID> allowed, String query) {
        authorize(actor, tenant);
        if (allowed.size() > 4020) throw ChatException.invalid("Too many files in search scope.");
        var scope = files.documents(tenant, actor, allowed);
        var hits = search.searchFiles(actor, tenant, scope, query);
        authorize(actor, tenant);
        var current = files.documents(tenant, actor, allowed);
        return hits.stream().flatMap(hit -> current.entrySet().stream().filter(entry -> entry.getValue().equals(hit.documentId()))
                .map(entry -> new FileHit(entry.getKey(), hit))).toList();
    }
    private void authorize(ActorId actor, TenantId tenant) {
        if (tenants.findActiveTenant(actor).filter(tenant::equals).isEmpty()) throw ChatException.unavailable();
    }

    public io.memoryos.retrieval.SearchDocument read(ActorId actor, UUID file, UUID generation, int from) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        var scope = files.documents(tenant, actor, Set.of(file));
        var document = scope.get(file);
        if (document == null) throw ChatException.unavailable();
        var result = search.fileDocument(actor, tenant, scope, document, generation, from);
        authorize(actor, tenant);
        if (!document.equals(files.documents(tenant, actor, Set.of(file)).get(file))) throw ChatException.unavailable();
        return result;
    }
}
