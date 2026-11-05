package com.confluo.platform.config;

import com.confluo.common.config.BaseOpenApiConfig;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The platform service's API document.
 */
@Configuration
public class OpenApiConfig implements BaseOpenApiConfig {

    @Override
    public String title() {
        return "Confluo Platform API";
    }

    @Override
    public String description() {
        return "Countries, currencies and languages the rest of the platform reads";
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
