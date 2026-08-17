package tech.migueldev.coffeewarehouse.api.exception;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.List;

/**
 * Single place where exceptions become HTTP responses.
 *
 * Every error body is an RFC 7807 {@code application/problem+json}, which
 * Spring 6 supports natively through {@link ProblemDetail} -- no custom error
 * envelope to invent, document and defend.
 *
 * Extending {@link ResponseEntityExceptionHandler} means the framework-level
 * failures (unreadable body, wrong method, missing parameter) already come out
 * in the same shape; only the field-level validation detail is overridden.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * Problem types are URNs rather than URLs: they are stable identifiers a
     * client can switch on, and this project has no documentation site to
     * dereference them against.
     */
    private static final String PROBLEM_TYPE_PREFIX = "urn:problem-type:";

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage(),
                "resource-not-found", request);
    }

    @ExceptionHandler(DuplicateCodeException.class)
    ProblemDetail handleDuplicateCode(DuplicateCodeException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Code already in use", ex.getMessage(),
                "duplicate-code", request);
    }

    /**
     * Backstop for the race the service check cannot close: two concurrent
     * creates both pass existsByCode and only one survives the unique index.
     * The loser gets the same 409 it would have gotten sequentially.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex,
                                               HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Constraint violation",
                "The request conflicts with a database constraint",
                "constraint-violation", request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "One or more fields are invalid");
        problem.setTitle("Validation failed");
        problem.setType(URI.create(PROBLEM_TYPE_PREFIX + "validation-failed"));

        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", violations);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail,
                                  String type, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(PROBLEM_TYPE_PREFIX + type));
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    /**
     * One invalid field, as it appears in the "errors" array of the problem body.
     */
    public record FieldViolation(String field, String message) {
    }
}
