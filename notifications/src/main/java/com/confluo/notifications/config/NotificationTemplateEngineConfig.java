package com.confluo.notifications.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/**
 * The engine that renders a message.
 *
 * <p>A {@link StringTemplateResolver}, because a template arrives here as a string out of MongoDB
 * rather than as a file on the classpath. The resolver treats what it is handed as the template
 * itself instead of as a name to look up, which is the whole mechanism - and it is also why
 * {@code spring.thymeleaf.check-template-location} is off: there is no
 * {@code classpath:/templates/} for this service to find.</p>
 *
 * <p>Deliberately a plain {@link TemplateEngine} and not a {@code SpringTemplateEngine}. Spring
 * Boot's own Thymeleaf auto-configuration backs off when it finds an {@code ISpringTemplateEngine}
 * bean, so declaring one here would replace the MVC view engine with this one and any view name
 * ever returned would be treated as a template body. A plain engine leaves that alone, and nothing
 * in a message needs what the Spring-aware engine adds.</p>
 *
 * <p>The parsed-template cache is left on, and keyed - unusually - by the template's whole text,
 * since that is what stands in for its name. That turns out to be exactly right here: an edited
 * template is a different key, so a render can never serve the wording from before the edit, and
 * Thymeleaf's own bounded cache ages the old text out on its own.</p>
 */
@Configuration
public class NotificationTemplateEngineConfig {

    /**
     * The engine the rendering service uses.
     *
     * <p>Named rather than left to type matching, because a second {@link TemplateEngine} - the
     * MVC one - exists alongside it.</p>
     *
     * @return the engine
     */
    @Bean("notificationTemplateEngine")
    public TemplateEngine notificationTemplateEngine() {
        TemplateEngine engine = new TemplateEngine();
        // The template mode is set per render, by the TemplateSpec, because a subject and a text
        // body must not be HTML-escaped and an HTML body must be.
        engine.setTemplateResolver(new StringTemplateResolver());
        return engine;
    }
}
