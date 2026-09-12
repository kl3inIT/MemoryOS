package io.memoryos.api.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.memoryos.FailureCategory;
import io.memoryos.iam.IdentityProvisioningException;
import io.memoryos.iam.IdentityProvisioningFailureReason;
import io.memoryos.iam.InvitationException;
import io.memoryos.iam.InvitationFailureReason;

import java.net.URI;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void validationProjectsOnlyStableCodesAndSafeConstraintParameters() throws Exception {
        var input = new ValidationInput("x", "private-invalid-email");
        var errors = new org.springframework.validation.BeanPropertyBindingResult(input, "input");
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            new org.springframework.validation.beanvalidation.SpringValidatorAdapter(factory.getValidator()).validate(input, errors);
        }
        var parameter = new org.springframework.core.MethodParameter(
                ApiExceptionHandlerTest.class.getDeclaredMethod("validationEndpoint", ValidationInput.class), 0);
        var problem = handler.handleRequestValidation(new org.springframework.web.bind.MethodArgumentNotValidException(parameter, errors));
        assertNotNull(problem.getProperties());
        var json = new tools.jackson.databind.ObjectMapper().valueToTree(problem.getProperties().get("errors"));
        assertEquals("EMAIL", json.get(0).path("code").asString());
        assertEquals("SIZE", json.get(1).path("code").asString());
        assertEquals(2, json.get(1).path("params").path("min").asInt());
        assertEquals(80, json.get(1).path("params").path("max").asInt());
        assertFalse(json.toString().contains("private-invalid-email"));
        assertFalse(json.toString().contains("rejectedValue"));
    }

    private record ValidationInput(@jakarta.validation.constraints.Size(min = 2, max = 80) String name,
                                   @jakarta.validation.constraints.Email String email) {}

    @SuppressWarnings("unused") // MethodParameter fixture, not a runtime endpoint.
    private static void validationEndpoint(ValidationInput input) {}

    @Test
    void mapsEveryInvitationFailureToOneSafeProblemContract() {
        for (InvitationFailureReason reason : InvitationFailureReason.values()) {
            var problem = handler.handleBusinessException(new InvitationException(
                    reason,
                    "diagnostic message that must stay server-side"
            ));

            assertEquals(status(reason.category()).value(), problem.getStatus());
            assertEquals(title(reason.category()), problem.getTitle());
            assertEquals(reason.message(), problem.getDetail());
            assertNotEquals("diagnostic message that must stay server-side", problem.getDetail());
            assertEquals(problemType(reason.code()), problem.getType());
            assertNotNull(problem.getProperties());
            assertEquals(reason.code(), problem.getProperties().get("code"));
        }
    }

    @Test
    void mapsEveryIdentityProvisioningFailureToOneSafeProblemContract() {
        for (IdentityProvisioningFailureReason reason : IdentityProvisioningFailureReason.values()) {
            var problem = handler.handleBusinessException(new IdentityProvisioningException(
                    reason,
                    "provider diagnostic that must stay server-side"
            ));

            assertEquals(status(reason.category()).value(), problem.getStatus());
            assertEquals(title(reason.category()), problem.getTitle());
            assertEquals(reason.message(), problem.getDetail());
            assertNotEquals("provider diagnostic that must stay server-side", problem.getDetail());
            assertEquals(problemType(reason.code()), problem.getType());
            assertNotNull(problem.getProperties());
            assertEquals(reason.code(), problem.getProperties().get("code"));
        }
    }

    private static HttpStatus status(FailureCategory category) {
        return switch (category) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case NOT_PERMITTED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case GONE -> HttpStatus.GONE;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private static String title(FailureCategory category) {
        return switch (category) {
            case VALIDATION -> "Validation failed";
            case NOT_PERMITTED -> "Not permitted";
            case NOT_FOUND -> "Not found";
            case CONFLICT -> "Conflict";
            case GONE -> "Unavailable";
            case SERVICE_UNAVAILABLE -> "Service unavailable";
        };
    }

    private static URI problemType(String code) {
        return URI.create(
                "urn:memoryos:failure:" + code.toLowerCase(Locale.ROOT).replace('_', '-')
        );
    }
}
