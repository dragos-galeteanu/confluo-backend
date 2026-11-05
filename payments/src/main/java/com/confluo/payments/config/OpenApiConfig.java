package com.confluo.payments.config;

import com.confluo.common.config.BaseOpenApiConfig;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The payments service's API document.
 */
@Configuration
public class OpenApiConfig implements BaseOpenApiConfig {

    @Override
    public String title() {
        return "Confluo Payments API";
    }

    @Override
    public String description() {
        return "Checkout, subscriptions, discounts and payouts: what was charged, what was earned, and what Stripe knows about it";
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
