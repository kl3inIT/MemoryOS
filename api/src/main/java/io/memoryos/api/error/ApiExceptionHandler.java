package io.memoryos.api.error;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;
import io.memoryos.connector.GoogleDriveProviderException;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import jakarta.validation.ConstraintViolation;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.ObjectError;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
final class ApiExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    ProblemDetail handleBusinessException(BusinessException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status(exception.category()),
                exception.safeMessage()
        );
        problem.setTitle(title(exception.category()));
        problem.setType(problemType(exception.code()));
        problem.setProperty("code", exception.code());
        return problem;
    }

    @ExceptionHandler(GoogleDriveProviderException.class)
    ProblemDetail handleGoogleDriveFailure(GoogleDriveProviderException exception) {
        HttpStatus status = switch (exception.failure()) {
            case AUTHENTICATION, INCONSISTENT -> HttpStatus.CONFLICT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNSUPPORTED, MALFORMED, LIMIT_EXCEEDED -> HttpStatus.BAD_REQUEST;
            case QUOTA, UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        String code = "GOOGLE_DRIVE_" + exception.failure().name();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Google Drive could not complete this operation.");
        problem.setTitle(status.getReasonPhrase());
        problem.setType(problemType(code));
        problem.setProperty("code", code);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleMalformedJson() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed JSON request.");
    }


    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleRequestValidation(MethodArgumentNotValidException exception) {
        List<ValidationError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> validationError(error.getField(), error,
                        error.contains(ConstraintViolation.class) ? error.unwrap(ConstraintViolation.class) : null))
                .sorted(ValidationError.ORDER)
                .toList();
        return validationProblem(errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail handleMethodValidation(HandlerMethodValidationException exception) {
        List<ValidationError> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> validationError(
                                result.getMethodParameter().getParameterName(),
                                error,
                                violation(result, error)
                        )))
                .sorted(ValidationError.ORDER)
                .toList();
        return validationProblem(errors);
    }

    private static ConstraintViolation<?> violation(org.springframework.validation.method.ParameterValidationResult result,
                                                     MessageSourceResolvable error) {
        if (error instanceof ObjectError objectError && objectError.contains(ConstraintViolation.class)) {
            return objectError.unwrap(ConstraintViolation.class);
        }
        try { return result.unwrap(error, ConstraintViolation.class); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static ValidationError validationError(String field, MessageSourceResolvable error, ConstraintViolation<?> violation) {
        String constraint = violation != null ? violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName()
                : error instanceof ObjectError objectError ? objectError.getCode() : null;
        String code = switch (constraint == null ? "" : constraint) {
            case "NotNull", "NotBlank", "NotEmpty" -> "REQUIRED";
            case "Email" -> "EMAIL";
            case "Size" -> "SIZE";
            case "Min", "DecimalMin", "PositiveOrZero" -> "MIN";
            case "Max", "DecimalMax" -> "MAX";
            default -> "INVALID";
        };
        Map<String, Number> params = new LinkedHashMap<>();
        if (violation != null) {
            var attributes = violation.getConstraintDescriptor().getAttributes();
            if (code.equals("SIZE")) {
                for (String name : List.of("min", "max")) {
                    if (attributes.get(name) instanceof Number number) params.put(name, number);
                }
            } else if (code.equals("MIN") || code.equals("MAX")) {
                if (attributes.get("value") instanceof Number number) params.put(code.toLowerCase(Locale.ROOT), number);
            }
        }
        return new ValidationError(field, safeMessage(error.getDefaultMessage()), code, Map.copyOf(params));
    }

    private static ProblemDetail validationProblem(List<ValidationError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "One or more request values are invalid."
        );
        problem.setTitle("Validation failed");
        problem.setType(problemType("REQUEST_VALIDATION"));
        problem.setProperty("code", "REQUEST_VALIDATION");
        problem.setProperty("errors", errors);
        return problem;
    }

    private static String safeMessage(String message) {
        return message == null || message.isBlank() ? "Invalid value." : message;
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

    private record ValidationError(String field, String message, String code, Map<String, Number> params) {
        private static final Comparator<ValidationError> ORDER =
                Comparator.comparing(ValidationError::field).thenComparing(ValidationError::message);

        private ValidationError {
            field = field == null || field.isBlank() ? "argument" : field;
        }
    }
}
