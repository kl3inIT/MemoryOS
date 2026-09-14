package io.memoryos.api.search;

import io.memoryos.iam.identity.IdentityContext;
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
    private final io.memoryos.retrieval.DocumentOriginalService originals;
    SearchController(DocumentSearchService service, io.memoryos.retrieval.DocumentOriginalService originals) {
        this.service = service;
        this.originals = originals;
    }

    @GetMapping(value = "/documents/{documentId}/original", produces = org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(operationId = "readSearchDocumentOriginal", summary = "Read the original PDF of a search result to show the matched page")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Original PDF bytes",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string", format = "binary")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "206", description = "Requested byte range of the original PDF",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string", format = "binary")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "416", description = "Requested byte range starts beyond the original PDF")
    void original(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID documentId, @RequestParam UUID generation,
            @Parameter(description = "One byte range, for example bytes=0-1048575")
            @org.springframework.web.bind.annotation.RequestHeader(value = org.springframework.http.HttpHeaders.RANGE, required = false) @org.jspecify.annotations.Nullable String range,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        DocumentOriginalResponses.write(range, response,
                requested -> originals.searchPdf(identity.actorId(), documentId, generation, requested));
    }

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
