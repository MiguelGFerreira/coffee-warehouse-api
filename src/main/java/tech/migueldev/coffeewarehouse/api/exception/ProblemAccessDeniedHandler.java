package tech.migueldev.coffeewarehouse.api.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * The answer to a caller who is authenticated and still may not do this.
 *
 * <p><b>403 rather than 404.</b> Hiding the existence of an endpoint behind a
 * "not found" is a defensible pattern when the resource itself is a secret. Here
 * the whole API surface is published at {@code /docs}, so pretending an endpoint
 * does not exist would confuse an honest operator without inconveniencing anyone
 * else.
 *
 * <p>The detail names the role that would have been enough. That is deliberate:
 * an operator who is told "this needs ADMIN" knows to ask someone, whereas a
 * bare "forbidden" sends them to the logs. The role names are already public --
 * they are in the OpenAPI description and in the token the caller is holding.
 */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemErrorWriter writer;

    public ProblemAccessDeniedHandler(ProblemErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {

        writer.write(request, response, HttpStatus.FORBIDDEN,
                "Insufficient role",
                "Your role does not permit %s %s".formatted(
                        request.getMethod(), request.getRequestURI()),
                "forbidden");
    }
}
