package io.memoryos.api.security;

import io.memoryos.identity.IdentityContext;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class ApplicationSessionController {

    @GetMapping("/")
    ApplicationSessionResponse currentSession(@AuthenticationPrincipal IdentityContext identityContext) {
        return new ApplicationSessionResponse(identityContext.actorId().value());
    }

    @GetMapping("/access-not-provisioned")
    ResponseEntity<AccessNotProvisionedResponse> accessNotProvisioned() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new AccessNotProvisionedResponse("ACCESS_NOT_PROVISIONED"));
    }


    record ApplicationSessionResponse(UUID actorId) {
    }

    record AccessNotProvisionedResponse(String reasonCode) {
    }
}
