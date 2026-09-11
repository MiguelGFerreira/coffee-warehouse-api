package tech.migueldev.coffeewarehouse.service;

import tech.migueldev.coffeewarehouse.domain.AppUser;
import tech.migueldev.coffeewarehouse.repository.AppUserRepository;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * Where Spring Security gets its users from.
 *
 * The adapter between the framework's {@link UserDetails} and this project's
 * {@link AppUser}, kept deliberately thin: the entity stays free of Spring
 * Security types, and the framework never sees a JPA entity.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    /**
     * <p><b>The same failure for an unknown user and a wrong password.</b>
     * Spring Security's {@code DaoAuthenticationProvider} deliberately answers
     * {@code BadCredentialsException} either way, and the login endpoint keeps
     * that shape. Distinguishing them turns the endpoint into an oracle for
     * which usernames exist.
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase();

        return users.findByUsername(normalized)
                .map(AuthenticatedUser::new)
                .orElseThrow(() -> new UsernameNotFoundException("Bad credentials"));
    }

    /**
     * A user as the framework sees it.
     *
     * Only {@code enabled} is modeled: expiry and credential rotation are real
     * concerns that this project does not have a story for, and returning
     * {@code true} from four methods that mean nothing would be worse than
     * saying here that they are out of scope.
     */
    public record AuthenticatedUser(AppUser user) implements UserDetails {

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return List.of(new SimpleGrantedAuthority(user.getRole().authority()));
        }

        @Override
        public String getPassword() {
            return user.getPasswordHash();
        }

        @Override
        public String getUsername() {
            return user.getUsername();
        }

        /**
         * A deactivated account fails authentication with {@code
         * DisabledException} before the password is ever compared.
         */
        @Override
        public boolean isEnabled() {
            return user.isActive();
        }
    }
}
