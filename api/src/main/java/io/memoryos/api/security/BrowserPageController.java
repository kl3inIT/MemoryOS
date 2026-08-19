package io.memoryos.api.security;

import io.memoryos.identity.IdentityContext;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class BrowserPageController {

    @GetMapping("/")
    BrowserSessionResponse home(@AuthenticationPrincipal IdentityContext identityContext) {
        return new BrowserSessionResponse(identityContext.actorId().value());
    }

    @GetMapping("/access-not-provisioned")
    ResponseEntity<BrowserFailureResponse> accessNotProvisioned() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new BrowserFailureResponse("ACCESS_NOT_PROVISIONED"));
    }


    record BrowserSessionResponse(UUID actorId) {
    }

    record BrowserFailureResponse(String reasonCode) {
    }
}
