package io.memoryos.api.error;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

import java.net.URI;
import java.util.Locale;
import java.util.Comparator;
import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(annotations = RestController.class)
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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleRequestValidation(MethodArgumentNotValidException exception) {
        List<ValidationError> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ValidationError(error.getField(), safeMessage(error.getDefaultMessage())))
                .sorted(ValidationError.ORDER)
                .toList();
        return validationProblem(errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail handleMethodValidation(HandlerMethodValidationException exception) {
        List<ValidationError> errors = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ValidationError(
                                result.getMethodParameter().getParameterName(),
                                safeMessage(error.getDefaultMessage())
                        )))
                .sorted(ValidationError.ORDER)
                .toList();
        return validationProblem(errors);
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
            case UNAVAILABLE -> HttpStatus.GONE;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private static String title(FailureCategory category) {
        return switch (category) {
            case VALIDATION -> "Validation failed";
            case NOT_PERMITTED -> "Not permitted";
            case NOT_FOUND -> "Not found";
            case CONFLICT -> "Conflict";
            case UNAVAILABLE -> "Unavailable";
            case SERVICE_UNAVAILABLE -> "Service unavailable";
        };
    }

    private static URI problemType(String code) {
        return URI.create(
                "urn:memoryos:failure:" + code.toLowerCase(Locale.ROOT).replace('_', '-')
        );
    }

    private record ValidationError(String field, String message) {
        private static final Comparator<ValidationError> ORDER =
                Comparator.comparing(ValidationError::field).thenComparing(ValidationError::message);

        private ValidationError {
            field = field == null || field.isBlank() ? "argument" : field;
        }
    }

}
