package com.fulfillops.common;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("fulfillops.security")
public record SecurityProperties(
        String jwtSecret,
        @DefaultValue("fulfillops") String issuer,
        @DefaultValue List<String> publicPaths) {
}
