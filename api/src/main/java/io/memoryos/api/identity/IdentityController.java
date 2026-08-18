package io.memoryos.api.identity;

import io.memoryos.identity.IdentityContext;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/identity")
final class IdentityController {

    @GetMapping("/me")
    CurrentIdentityResponse currentIdentity(@AuthenticationPrincipal IdentityContext identityContext) {
        return new CurrentIdentityResponse(identityContext.actorId().value());
    }

    record CurrentIdentityResponse(UUID actorId) {
    }
}
