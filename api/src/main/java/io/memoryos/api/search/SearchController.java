package io.memoryos.api.search;

import io.memoryos.iam.IdentityContext;
import io.memoryos.api.search.contract.SearchDocumentResponse;
import io.memoryos.api.search.contract.SearchPageResponse;
import io.memoryos.retrieval.SearchRequest;
import io.memoryos.retrieval.DocumentSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@Tag(name = "Search")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
class SearchController {
    private final DocumentSearchService service;
    SearchController(DocumentSearchService service) { this.service = service; }

    @PostMapping
    @Operation(operationId = "searchDocuments", summary = "Search current documents with keyword and semantic retrieval")
    SearchPageResponse search(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @RequestBody SearchRequest request) {
        return SearchPageResponse.from(service.search(identity.actorId(), request));
    }

    @GetMapping("/documents/{documentId}")
    @Operation(operationId = "getSearchDocument", summary = "Read current document passages around a search result")
    SearchDocumentResponse document(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID documentId, @RequestParam UUID generation, @RequestParam(defaultValue = "0") int from) {
        return SearchDocumentResponse.from(service.document(identity.actorId(), documentId, generation, from));
    }
}
