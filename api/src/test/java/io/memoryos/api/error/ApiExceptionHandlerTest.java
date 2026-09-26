package io.memoryos.api.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    /** The public status and title of each failure category: one literal row per category. */
    @ParameterizedTest
    @CsvSource({
            "VALIDATION, 400, Validation failed",
            "NOT_PERMITTED, 403, Not permitted",
            "NOT_FOUND, 404, Not found",
            "CONFLICT, 409, Conflict",
            "GONE, 410, Unavailable",
            "LIMIT_EXCEEDED, 429, Limit reached",
            "SERVICE_UNAVAILABLE, 503, Service unavailable",
    })
    void mapsEachFailureCategoryToOneSafeProblemContract(FailureCategory category, int status, String title) {
        var problem = handler.handleBusinessException(new CategoryFailure(category));

        assertEquals(status, problem.getStatus());
        assertEquals(title, problem.getTitle());
        assertEquals("Safe message.", problem.getDetail());
        assertEquals(URI.create("urn:memoryos:failure:sample-failure-2"), problem.getType());
        assertNotNull(problem.getProperties());
        assertEquals("SAMPLE_FAILURE_2", problem.getProperties().get("code"));
    }

    private static final class CategoryFailure extends BusinessException {
        CategoryFailure(FailureCategory category) {
            super("SAMPLE_FAILURE_2", category, "Safe message.", "diagnostic message that must stay server-side");
        }
    }
}
