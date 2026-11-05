package com.confluo.notifications.config;

import com.confluo.common.config.BaseOpenApiConfig;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The notifications service's API document.
 */
@Configuration
public class OpenApiConfig implements BaseOpenApiConfig {

    @Override
    public String title() {
        return "Confluo Notifications API";
    }

    @Override
    public String description() {
        return "Email and SMS: what was sent, to whom, and the templates and preferences that decided it";
    }

    /**
     * Publishes the document.
     * @return the OpenAPI document
     */
    @Bean
    @Override
    public OpenAPI openApi() {
        return BaseOpenApiConfig.super.openApi();
    }
}
