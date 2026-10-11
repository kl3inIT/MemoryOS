package io.memoryos.api.ocr;

import io.memoryos.ai.AiException;
import io.memoryos.ai.ProviderCredentials;
import io.memoryos.ai.ocr.OcrConnectionService;
import io.memoryos.ai.ocr.OcrProbe;
import io.memoryos.ai.ocr.OcrProvider;
import io.memoryos.api.ocr.contract.OcrConnectionRequest;
import io.memoryos.api.ocr.contract.OcrConnectionResponse;
import io.memoryos.api.ocr.contract.OcrConnectionTestRequest;
import io.memoryos.api.ocr.contract.OcrProviderResponse;
import io.memoryos.api.ocr.contract.OcrSelectionRequest;
import io.memoryos.api.security.CurrentActor;
import io.memoryos.iam.IdentityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/ocr", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "OCR")
@SecurityRequirement(name = "browserSession")
@SecurityRequirement(name = "bearerAuth")
@ApiResponse(responseCode = "400", description = "Invalid OCR configuration")
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Management authority or CSRF required")
@ApiResponse(responseCode = "404", description = "OCR connection unavailable")
@ApiResponse(responseCode = "409", description = "OCR connection changed")
@ApiResponse(responseCode = "503", description = "OCR service unavailable")
class OcrConnectionController {
    private final OcrConnectionService connections;
    private final OcrProbe probe;

    OcrConnectionController(OcrConnectionService connections, OcrProbe probe) {
        this.connections = connections; this.probe = probe;
    }

    @GetMapping("/providers")
    @ApiResponse(responseCode = "200", description = "Installed OCR providers", useReturnTypeSchema = true)
    @Operation(operationId = "listOcrProviders", summary = "List the installed OCR providers and what a connection of each needs")
    List<OcrProviderResponse> providers(@CurrentActor IdentityContext identity) {
        return connections.providers(identity.actorId()).stream().map(OcrProviderResponse::from).toList();
    }

    @GetMapping("/connections")
    @ApiResponse(responseCode = "200", description = "OCR connections", useReturnTypeSchema = true)
    @Operation(operationId = "listOcrConnections", summary = "List OCR connections for model managers; the active one reads scans and images")
    List<OcrConnectionResponse> list(@CurrentActor IdentityContext identity) {
        return connections.list(identity.actorId()).stream().map(OcrConnectionResponse::from).toList();
    }

    @PutMapping("/connections/{provider}")
    @ApiResponse(responseCode = "200", description = "Saved OCR connection", useReturnTypeSchema = true)
    @Operation(operationId = "saveOcrConnection", summary = "Configure one OCR connection without choosing it as the reader")
    OcrConnectionResponse save(@CurrentActor IdentityContext identity, @PathVariable OcrProvider provider,
            @Valid @RequestBody OcrConnectionRequest request) {
        return OcrConnectionResponse.from(connections.save(identity.actorId(), provider,
                new OcrConnectionService.Input(request.endpoint(),
                        new ProviderCredentials.Change(request.credentialAction(), request.credentialValue()),
                        request.dataBoundary(), request.revision())));
    }

    @PutMapping("/selection")
    @ApiResponse(responseCode = "204", description = "OCR reader saved", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "selectOcrProvider", summary = "Choose the connection that reads scans and images; null returns them to Docling's own OCR")
    void select(@CurrentActor IdentityContext identity, @Valid @RequestBody OcrSelectionRequest request) {
        connections.select(identity.actorId(), request.provider());
    }

    @PostMapping("/connections/{provider}/test")
    @ApiResponse(responseCode = "204", description = "The connection read the test picture", content = @Content)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "testOcrConnection", summary = "Read a small test picture with a connection; a cloud provider may charge for it")
    void test(@CurrentActor IdentityContext identity, @PathVariable OcrProvider provider,
            @Valid @RequestBody(required = false) OcrConnectionTestRequest request) {
        var test = connections.forTest(identity.actorId(), provider,
                request == null ? null : new OcrConnectionService.ProbeInput(request.endpoint(), request.credentialValue()));
        String key = test.credential() != null ? test.credential() : connections.key(test.connection());
        if (!probe.reads(test.connection(), key)) throw AiException.providerUnavailable();
    }
}
