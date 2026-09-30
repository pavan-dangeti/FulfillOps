package com.fulfillops.common;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param signer     true only in the service that issues tokens (order-service)
 * @param jwkSetUri  where every other service fetches the signer's public keys
 */
@ConfigurationProperties("fulfillops.security")
public record SecurityProperties(
        boolean signer,
        String jwkSetUri,
        @DefaultValue("fulfillops") String issuer,
        @DefaultValue List<String> publicPaths) {
}
