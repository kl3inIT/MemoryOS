package io.memoryos.api.search;

import io.memoryos.iam.identity.IdentityContext;
import io.memoryos.api.search.contract.DocumentSpreadsheetResponse;
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
    @Operation(operationId = "readSearchDocumentOriginal", summary = "Read the stored original of a search result, of any media type, to show the file as it looks")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Original bytes, under the object's declared media type",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string", format = "binary")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "206", description = "Requested byte range of the original",
            content = @io.swagger.v3.oas.annotations.media.Content(mediaType = org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE,
                    schema = @io.swagger.v3.oas.annotations.media.Schema(type = "string", format = "binary")))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "416", description = "Requested byte range starts beyond the original")
    void original(@Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID documentId, @RequestParam UUID generation,
            @Parameter(description = "One byte range, for example bytes=0-1048575")
            @org.springframework.web.bind.annotation.RequestHeader(value = org.springframework.http.HttpHeaders.RANGE, required = false) @org.jspecify.annotations.Nullable String range,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        DocumentOriginalResponses.write(range, response,
                requested -> originals.searchOriginal(identity.actorId(), documentId, generation, requested));
    }

    @GetMapping("/documents/{documentId}/spreadsheet")
    @Operation(operationId = "readSearchDocumentSpreadsheet", summary = "Read the workbook original of a search result as CSV text per sheet")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Sheets in workbook order", useReturnTypeSchema = true)
    org.springframework.http.ResponseEntity<DocumentSpreadsheetResponse> spreadsheet(
            @Parameter(hidden = true) @AuthenticationPrincipal IdentityContext identity,
            @PathVariable UUID documentId, @RequestParam UUID generation) {
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(DocumentSpreadsheetResponse.from(originals.searchWorkbook(identity.actorId(), documentId, generation)));
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
