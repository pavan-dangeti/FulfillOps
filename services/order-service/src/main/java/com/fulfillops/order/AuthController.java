package com.fulfillops.order;

import com.fulfillops.common.TokenIssuer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Exchanges a username and password for a one-hour access token.
 * Accounts come from configuration (env vars), not a user table: this system
 * has a handful of operator accounts, not customer sign-up.
 *
 * <p>After {@value #FREE_ATTEMPTS} consecutive wrong passwords an account is
 * refused (429) until a minute has passed since the last failure, which caps
 * online guessing at a few attempts per minute while a locked-out operator
 * recovers on their own. Only configured accounts are tracked, so the map
 * cannot be grown with made-up usernames.
 */
// Known limit: the failure count is per instance and in memory; with several replicas it
// belongs in a shared store, and per-IP limits belong at the reverse proxy.
@RestController
@RequestMapping("/api/auth")
@EnableConfigurationProperties(AuthController.Accounts.class)
public class AuthController {

    static final int FREE_ATTEMPTS = 5;
    static final Duration COOL_DOWN = Duration.ofMinutes(1);

    private record Failures(int count, Instant last) {
    }

    private final Accounts accounts;
    private final TokenIssuer tokens;
    private final Clock clock = Clock.systemUTC();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    // Compared against when the username is unknown, so response time doesn't reveal which usernames exist.
    private final String dummyHash = encoder.encode("not-a-real-password");

    public AuthController(Accounts accounts, TokenIssuer tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    @PostMapping("/token")
    public TokenResponse token(@Valid @RequestBody Credentials body) {
        Account account = accounts.users().stream()
                .filter(a -> a.username().equals(body.username()) && a.password() != null && !a.password().isBlank())
                .findFirst()
                .orElse(null);
        Instant now = clock.instant();
        if (account != null && coolingDown(account.username(), now)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed sign-ins; try again in a minute");
        }
        boolean ok = encoder.matches(body.password(), account == null ? dummyHash : account.password());
        if (account == null || !ok) {
            if (account != null) {
                failures.merge(account.username(), new Failures(1, now), (a, b) -> new Failures(a.count() + 1, now));
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        failures.remove(account.username());
        return new TokenResponse(tokens.issue(account.username(), account.roles()), "Bearer",
                TokenIssuer.TTL.toSeconds(), account.roles());
    }

    private boolean coolingDown(String username, Instant now) {
        Failures f = failures.get(username);
        return f != null && f.count() >= FREE_ATTEMPTS && now.isBefore(f.last().plus(COOL_DOWN));
    }

    public record Credentials(@NotBlank String username, @NotBlank String password) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn, List<String> roles) {
    }

    /** Passwords use Spring's {id}-prefixed format, e.g. {bcrypt}$2a$10$... ({noop} is for local dev only). */
    public record Account(String username, String password, List<String> roles) {
    }

    @ConfigurationProperties("fulfillops.auth")
    public record Accounts(@DefaultValue List<Account> users) {
    }
}
