package com.fulfillops.common;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** Signs the short-lived access tokens every service accepts. */
public class TokenIssuer {

    public static final Duration TTL = Duration.ofHours(1);

    private final JwtEncoder encoder;
    private final String issuer;

    public TokenIssuer(JwtEncoder encoder, String issuer) {
        this.encoder = encoder;
        this.issuer = issuer;
    }

    public String issue(String subject, List<String> roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(TTL))
                .claim("roles", roles)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
