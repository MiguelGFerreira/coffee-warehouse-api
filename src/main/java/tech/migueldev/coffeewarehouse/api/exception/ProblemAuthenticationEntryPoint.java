package tech.migueldev.coffeewarehouse.api.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * The answer to a request that carries no usable identity: no token, an expired
 * one, a bad signature, a foreign issuer.
 *
 * <p><b>401 means "who are you?", not "you may not".</b> It is paired with
 * {@code WWW-Authenticate: Bearer} because RFC 6750 requires a 401 to say which
 * scheme would satisfy it -- dropping the header to gain a tidy body would trade
 * a standard for a preference.
 *
 * <p>The detail deliberately says nothing about <em>why</em> the token was
 * unacceptable. "Expired" versus "bad signature" is useful to a developer and
 * equally useful to someone probing with forged tokens, and the distinction
 * is available in the logs where only the operator sees it.
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemErrorWriter writer;

    public ProblemAuthenticationEntryPoint(ProblemErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {

        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        writer.write(request, response, HttpStatus.UNAUTHORIZED,
                "Authentication required",
                "A valid bearer token is required; obtain one from POST /api/auth/login",
                "unauthenticated");
    }
}
