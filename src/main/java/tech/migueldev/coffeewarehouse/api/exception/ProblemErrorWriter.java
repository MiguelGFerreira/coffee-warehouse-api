package tech.migueldev.coffeewarehouse.api.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

/**
 * Writes a {@link ProblemDetail} straight onto the response.
 *
 * Needed because the two most common errors this API can return never reach
 * {@code ApiExceptionHandler}. Authentication and authorization fail inside the
 * filter chain, before the {@code DispatcherServlet} has picked a handler, so
 * {@code @RestControllerAdvice} is not in the picture and Spring Security writes
 * its own empty body instead.
 *
 * Every other error here has been {@code application/problem+json} since Phase
 * 2. A client that parses one shape for thirteen error types and a different,
 * emptier one for the two it will hit most often is being made to work around
 * an inconsistency that costs this one small class to remove.
 */
@Component
public class ProblemErrorWriter {

    private static final String PROBLEM_TYPE_PREFIX = "urn:problem-type:";

    private final ObjectMapper objectMapper;

    public ProblemErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response,
                      HttpStatus status, String title, String detail, String type)
            throws IOException {

        // Something may already have been written -- a handler that failed
        // partway through. Appending a problem body to it would produce
        // malformed JSON rather than a clearer error.
        if (response.isCommitted()) {
            return;
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(PROBLEM_TYPE_PREFIX + type));
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
