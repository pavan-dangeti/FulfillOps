package com.fulfillops.cs.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the ops-floor visualization (Vite dev server on 5174) against the
 * new read-only /api/ops feed only. The Thymeleaf routes are same-origin and
 * gain nothing from cross-origin access, so they stay uncovered.
 */
@Configuration
public class OpsCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/ops/**")
                .allowedOrigins("http://localhost:5174")
                .allowedMethods("GET");
    }
}
