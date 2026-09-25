package io.memoryos.api.security;

import io.swagger.v3.oas.annotations.Parameter;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * The authenticated actor of the current request, as the {@code IdentityContext} principal both API filter chains
 * install. It is resolved by Spring Security, never bound from the request, so it is hidden from the OpenAPI
 * contract.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@AuthenticationPrincipal
@Parameter(hidden = true)
public @interface CurrentActor {
}
