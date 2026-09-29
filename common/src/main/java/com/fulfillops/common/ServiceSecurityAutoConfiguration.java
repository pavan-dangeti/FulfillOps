package com.fulfillops.common;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

/**
 * Every service is a stateless OAuth2 resource server: requests carry a
 * Bearer JWT, the {@code roles} claim becomes {@code ROLE_*} authorities, and
 * controllers guard endpoints with {@code @PreAuthorize}.
 *
 * <p>Exactly one service is the signer ({@code fulfillops.security.signer=true}):
 * it holds the RS256 private key and publishes only the public key at
 * {@value #JWKS_PATH}. Every other service verifies tokens against that JWKS
 * and has no key that could sign one.
 */
// Known limit: the signing key is generated at startup and kept in memory, so restarting the
// signer invalidates issued tokens and the signer cannot run as several replicas. Load the key
// from a secret store to lift both.
@AutoConfiguration
@EnableMethodSecurity
@EnableConfigurationProperties(SecurityProperties.class)
@Import(ApiErrors.class)
public class ServiceSecurityAutoConfiguration {

    public static final String JWKS_PATH = "/.well-known/jwks.json";

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "fulfillops.security.signer", havingValue = "true")
    static class Signer {

        @Bean
        RSAKey signingKey() throws JOSEException {
            return new RSAKeyGenerator(2048)
                    .keyID(UUID.randomUUID().toString())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
        }

        @Bean
        JwtEncoder jwtEncoder(RSAKey key) {
            return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        }

        @Bean
        TokenIssuer tokenIssuer(JwtEncoder encoder, SecurityProperties props) {
            return new TokenIssuer(encoder, props.issuer());
        }

        @Bean
        JwtDecoder jwtDecoder(RSAKey key, SecurityProperties props) throws JOSEException {
            return withIssuer(NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
                    .signatureAlgorithm(SignatureAlgorithm.RS256).build(), props);
        }

        /** Registered with Signer as a nested component, so it exists only on the signer. */
        @RestController
        static class JwksEndpoint {

            private final Map<String, Object> jwks;

            JwksEndpoint(RSAKey key) {
                this.jwks = new JWKSet(key.toPublicJWK()).toJSONObject();
            }

            @GetMapping(JWKS_PATH)
            Map<String, Object> jwks() {
                return jwks;
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "fulfillops.security.signer", havingValue = "false", matchIfMissing = true)
    static class Verifier {

        @Bean
        JwtDecoder jwtDecoder(SecurityProperties props) {
            if (props.jwkSetUri() == null || props.jwkSetUri().isBlank()) {
                throw new IllegalStateException("fulfillops.security.jwk-set-uri must point at the signer's " + JWKS_PATH);
            }
            // Fetched on first use and cached; an unknown key id (signer restarted) triggers a refetch.
            // Explicit timeouts: the library default (~0.5s) is too tight while the signer is still warming up.
            SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
            http.setConnectTimeout(Duration.ofSeconds(5));
            http.setReadTimeout(Duration.ofSeconds(5));
            return withIssuer(NimbusJwtDecoder.withJwkSetUri(props.jwkSetUri())
                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                    .restOperations(new RestTemplate(http))
                    .build(), props);
        }
    }

    private static NimbusJwtDecoder withIssuer(NimbusJwtDecoder decoder, SecurityProperties props) {
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.issuer()));
        return decoder;
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, SecurityProperties props) throws Exception {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);

        return http
                .csrf(AbstractHttpConfigurer::disable) // no cookies or sessions: Bearer tokens only
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", JWKS_PATH).permitAll()
                        .requestMatchers(props.publicPaths().toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
                .build();
    }
}
