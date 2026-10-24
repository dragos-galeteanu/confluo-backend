package com.confluo.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base OpenAPI configuration class for describing a module's API.
 */
public interface BaseOpenApiConfig {

    Logger log = LoggerFactory.getLogger(BaseOpenApiConfig.class);

    /** name of the bearer scheme, referenced by the security requirement below */
    String BEARER_SCHEME = "bearerAuth";

    /**
     * Title of the module's API, shown at the top of the Swagger UI.
     * @return the title of the API
     */
    String title();

    /**
     * Short summary of what the module's API covers.
     * @return the description of the API
     */
    String description();

    /**
     * Version of the module's API.
     * @return the version of the API
     */
    default String version() {
        return "v1";
    }

    /**
     * Documents the API and declares the Firebase bearer token as its security scheme, so that
     * the Swagger UI offers an "Authorize" button rather than leaving callers to hand-craft the header.
     * @return the OpenAPI document
     */
    default OpenAPI openApi() {
        log.info("Building OpenAPI document [title={}, version={}]", title(), version());

        return new OpenAPI()
                .info(new Info()
                        .title(title())
                        .description(description())
                        .version(version()))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Firebase ID token")));
    }
}
