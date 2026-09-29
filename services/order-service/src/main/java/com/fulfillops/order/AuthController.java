package com.fulfillops.order;

import com.fulfillops.common.TokenIssuer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
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
 */
// Known limit: no login rate limiting; add it at the reverse proxy before exposing this publicly.
@RestController
@RequestMapping("/api/auth")
@EnableConfigurationProperties(AuthController.Accounts.class)
public class AuthController {

    private final Accounts accounts;
    private final TokenIssuer tokens;
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
        boolean ok = encoder.matches(body.password(), account == null ? dummyHash : account.password());
        if (account == null || !ok) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        return new TokenResponse(tokens.issue(account.username(), account.roles()), "Bearer",
                TokenIssuer.TTL.toSeconds(), account.roles());
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
