package com.fulfillops.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class TokenVerificationTest {

    private static final SecurityProperties PROPS = new SecurityProperties(true, null, "fulfillops", List.of());

    private final ServiceSecurityAutoConfiguration.Signer signer = new ServiceSecurityAutoConfiguration.Signer();

    private record Keys(RSAKey key, TokenIssuer issuer, JwtDecoder decoder) {
    }

    private Keys keys() throws Exception {
        RSAKey key = signer.signingKey();
        return new Keys(key, signer.tokenIssuer(signer.jwtEncoder(key), PROPS), signer.jwtDecoder(key, PROPS));
    }

    @Test
    void acceptsItsOwnTokenAndReadsTheRoles() throws Exception {
        Keys a = keys();
        var jwt = a.decoder().decode(a.issuer().issue("seller", List.of("SELLER")));
        assertThat(jwt.getSubject()).isEqualTo("seller");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("SELLER");
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() throws Exception {
        Keys a = keys();
        Keys b = keys();
        assertThatThrownBy(() -> a.decoder().decode(b.issuer().issue("seller", List.of("CS"))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAnHmacTokenKeyedWithThePublicKey() throws Exception {
        // Algorithm confusion: an attacker who knows the public key signs HS256 with it.
        Keys a = keys();
        byte[] publicKeyBytes = a.key().toRSAPublicKey().getEncoded();
        SignedJWT forged = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims("fulfillops", Instant.now().plusSeconds(600)));
        forged.sign(new MACSigner(publicKeyBytes));
        assertThatThrownBy(() -> a.decoder().decode(forged.serialize())).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAnotherIssuerAndExpiredTokens() throws Exception {
        Keys a = keys();
        for (JWTClaimsSet bad : List.of(
                claims("someone-else", Instant.now().plusSeconds(600)),
                claims("fulfillops", Instant.now().minusSeconds(3600)))) {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(a.key().getKeyID()).build(), bad);
            jwt.sign(new RSASSASigner(a.key()));
            assertThatThrownBy(() -> a.decoder().decode(jwt.serialize())).isInstanceOf(JwtException.class);
        }
    }

    private static JWTClaimsSet claims(String issuer, Instant expires) {
        return new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("attacker")
                .issueTime(Date.from(expires.minusSeconds(3600)))
                .expirationTime(Date.from(expires))
                .claim("roles", List.of("CS"))
                .build();
    }
}
