package tech.migueldev.coffeewarehouse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * An account that authenticates against the API.
 *
 * The username is the identity, the same way a business code is the identity of
 * a producer: unique in the schema, assigned at creation, never changed.
 *
 * <h2>What this entity refuses to do</h2>
 *
 * It never sees a plaintext password. The hash arrives already encoded, because
 * choosing and applying the algorithm is the security configuration's job, not
 * the entity's -- an entity that called {@code BCrypt} would tie the domain to
 * one encoder and make the hash untestable without it.
 *
 * It also has no {@code toString} carrying the hash, no getter that returns it
 * under a friendly name, and no DTO anywhere maps it. The only way the hash
 * leaves this object is the one method that exists to compare it.
 */
@Entity
@Table(name = "app_user")
public class AppUser extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 60)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 150)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(nullable = false)
    private boolean active;

    protected AppUser() {
        // required by JPA
    }

    public AppUser(String username, String passwordHash, String displayName, UserRole role) {
        this.username = normalizeUsername(username);
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = role;
        this.active = true;
    }

    /**
     * Deactivating rather than deleting. The movements and shipments an account
     * touched still refer to a person who existed, and removing the row would
     * make the history harder to read for no gain -- the same instinct the
     * append-only ledger is built on.
     */
    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /**
     * Usernames are stored and compared lowercase, otherwise "Miguel" and
     * "miguel" would both pass the unique constraint and be the same person
     * twice. The schema enforces the character set with a CHECK; normalizing
     * here turns a mixed-case input into a valid row rather than a violation.
     */
    private static String normalizeUsername(String username) {
        return username == null ? null : username.trim().toLowerCase();
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    /**
     * Only the authentication path has any business calling this, and only to
     * hand it to a {@code PasswordEncoder} for comparison.
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof AppUser user && Objects.equals(username, user.username);
    }

    @Override
    public int hashCode() {
        return Objects.hash(username);
    }

    /**
     * Deliberately without the hash. A {@code toString} is the easiest way for a
     * credential to end up in a log file, and the one place it would happen is
     * an exception message nobody wrote on purpose.
     */
    @Override
    public String toString() {
        return "AppUser[username=%s, role=%s]".formatted(username, role);
    }
}
