package com.fulfillops.cs.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * CS reps sign in with their order-service account. The console exchanges the
 * password for an access token once, keeps the token in the server-side
 * session (never in the browser), and forwards it on every order-service call.
 */
@Configuration
public class SecurityConfig {

    /** The order-service access token, carried in the Authentication's details. */
    public record AccessToken(String value) {
    }

    record TokenRequest(String username, String password) {
    }

    public record TokenResponse(String accessToken, long expiresIn, List<String> roles) {
    }

    @Bean
    RestClient orderService(RestClient.Builder builder, @Value("${fulfillops.order-service-url}") String baseUrl) {
        return builder.baseUrl(baseUrl)
                .requestInterceptor((request, body, execution) -> {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    if (!request.getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)
                            && auth != null && auth.getDetails() instanceof AccessToken token) {
                        request.getHeaders().setBearerAuth(token.value());
                    }
                    return execution.execute(request, body);
                })
                .build();
    }

    /** Returns the token response, or null if order-service rejected the credentials. */
    public static TokenResponse login(RestClient orderService, String username, String password) {
        try {
            return orderService.post().uri("/api/auth/token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TokenRequest(username, password))
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.BadRequest e) {
            return null;
        }
    }

    @Bean
    AuthenticationProvider orderServiceLogin(RestClient orderService) {
        return new AuthenticationProvider() {
            @Override
            public Authentication authenticate(Authentication auth) {
                TokenResponse token = login(orderService, auth.getName(), String.valueOf(auth.getCredentials()));
                if (token == null || !token.roles().contains("CS")) {
                    throw new BadCredentialsException("Invalid username or password");
                }
                var result = UsernamePasswordAuthenticationToken.authenticated(auth.getName(), null,
                        token.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
                result.setDetails(new AccessToken(token.accessToken()));
                return result;
            }

            @Override
            public boolean supports(Class<?> type) {
                return UsernamePasswordAuthenticationToken.class.isAssignableFrom(type);
            }
        };
    }

    @Bean
    SecurityFilterChain console(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/login", "/css/**", "/js/**", "/actuator/health").permitAll()
                        // read-only ops feed; authenticates to order-service with its own OPS account
                        .requestMatchers("/api/ops/**").permitAll()
                        .anyRequest().hasRole("CS"))
                .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/orders", true).permitAll())
                .logout(l -> l.logoutSuccessUrl("/login?logout"))
                .build();
    }
}
