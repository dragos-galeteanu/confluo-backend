package com.confluo.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link BaseOpenApiConfig}: the document a module gets for free once it supplies a title
 * and a description.
 */
class BaseOpenApiConfigTest {

    /** a module that accepts the default version */
    private static final class DefaultVersionConfig implements BaseOpenApiConfig {

        @Override
        public String title() {
            return "Widgets API";
        }

        @Override
        public String description() {
            return "Everything about widgets";
        }
    }

    /** a module that pins its own version */
    private static final class PinnedVersionConfig implements BaseOpenApiConfig {

        @Override
        public String title() {
            return "Gadgets API";
        }

        @Override
        public String description() {
            return "Everything about gadgets";
        }

        @Override
        public String version() {
            return "v2";
        }
    }

    @Test
    void theInfoSectionComesFromTheModule() {
        OpenAPI openApi = new DefaultVersionConfig().openApi();

        assertThat(openApi.getInfo().getTitle()).isEqualTo("Widgets API");
        assertThat(openApi.getInfo().getDescription()).isEqualTo("Everything about widgets");
        assertThat(openApi.getInfo().getVersion()).isEqualTo("v1");
    }

    @Test
    void theVersionDefaultsToV1AndCanBeOverridden() {
        assertThat(new DefaultVersionConfig().version()).isEqualTo("v1");
        assertThat(new PinnedVersionConfig().openApi().getInfo().getVersion()).isEqualTo("v2");
    }

    @Test
    void theBearerSchemeIsDeclaredAsAFirebaseJwt() {
        OpenAPI openApi = new DefaultVersionConfig().openApi();

        SecurityScheme scheme = openApi.getComponents().getSecuritySchemes()
                .get(BaseOpenApiConfig.BEARER_SCHEME);

        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(scheme.getScheme()).isEqualTo("bearer");
        assertThat(scheme.getBearerFormat()).isEqualTo("JWT");
        assertThat(scheme.getDescription()).isEqualTo("Firebase ID token");
    }

    @Test
    void theDocumentRequiresTheBearerScheme() {
        OpenAPI openApi = new DefaultVersionConfig().openApi();

        assertThat(openApi.getSecurity()).hasSize(1);
        assertThat(openApi.getSecurity().get(0)).containsKey(BaseOpenApiConfig.BEARER_SCHEME);
    }

    @Test
    void theSchemeNameIsTheOneTheRequirementReferences() {
        assertThat(BaseOpenApiConfig.BEARER_SCHEME).isEqualTo("bearerAuth");
    }
}
