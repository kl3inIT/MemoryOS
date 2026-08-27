package io.memoryos.api.error;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

import java.net.URI;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    private static HttpStatus status(FailureCategory category) {
        return switch (category) {
            case VALIDATION -> HttpStatus.BAD_REQUEST;
            case NOT_PERMITTED -> HttpStatus.FORBIDDEN;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNAVAILABLE -> HttpStatus.GONE;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    private static String title(FailureCategory category) {
        return switch (category) {
            case VALIDATION -> "Validation failed";
            case NOT_PERMITTED -> "Not permitted";
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
}
