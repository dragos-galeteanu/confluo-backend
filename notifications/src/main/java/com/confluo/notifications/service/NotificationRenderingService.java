package com.confluo.notifications.service;

import com.confluo.notifications.channel.Channel;
import com.confluo.notifications.document.NotificationTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a template and a set of variables into the words that will be sent.
 *
 * <p>The variables are checked against what the template says it needs <em>before</em> anything is
 * rendered. Thymeleaf is happy to resolve a missing variable to nothing, which is the failure worth
 * preventing: an email that arrives saying "Hello ," has already been sent, while a render that
 * refuses to run is a 422 nobody receives.</p>
 *
 * <p>Each field is rendered in the mode that suits it. A subject and a text body go through
 * {@link TemplateMode#TEXT}, which does not escape - an ampersand in somebody's name should arrive
 * as an ampersand. An HTML body goes through {@link TemplateMode#HTML}, which does escape, and that
 * is not a detail: a variable is somebody's name or somebody's note, and an unescaped one is how a
 * message becomes an injection into every inbox it reaches.</p>
 */
@Service
@Slf4j
public class NotificationRenderingService {

    /**
     * Roughly one GSM-7 segment. Over this an SMS is billed as several, which is worth a line in
     * the log rather than a refusal: whether to pay for two segments is not this service's call.
     */
    private static final int SMS_SEGMENT_LENGTH = 160;

    private final TemplateEngine engine;

    /**
     * @param engine the string-resolving engine, named because the MVC one exists alongside it
     */
    public NotificationRenderingService(@Qualifier("notificationTemplateEngine") TemplateEngine engine) {
        this.engine = engine;
    }

    /**
     * Renders a template.
     *
     * @param template the wording to use
     * @param variables what to fill it in with; extras are allowed and ignored
     * @return the rendered subject and bodies
     * @throws IllegalStateException if the template declares a variable that was not supplied
     */
    public NotificationRenderedContent render(NotificationTemplate template, Map<String, Object> variables) {
        requireDeclaredVariables(template, variables);

        Context context = new Context();
        context.setVariables(variables);

        String subject = renderField(template.getSubject(), TemplateMode.TEXT, context);
        String textBody = renderField(template.getTextBody(), TemplateMode.TEXT, context);
        String htmlBody = renderField(template.getHtmlBody(), TemplateMode.HTML, context);

        if (template.getChannel() == Channel.SMS && textBody != null
                && textBody.length() > SMS_SEGMENT_LENGTH) {
            log.warn("Rendered SMS is {} characters and will be billed as several segments "
                            + "[key={}, locale={}]",
                    textBody.length(), template.getTemplateKey(), template.getLocale());
        }

        return new NotificationRenderedContent(subject, textBody, htmlBody);
    }

    /**
     * Refuses a render that would quietly leave a blank where something should be.
     *
     * <p>A declared variable that was passed as null counts as missing, because that is what
     * Thymeleaf would turn into an empty string - the whole thing being guarded against.</p>
     *
     * @param template the template and its declared variables
     * @param variables what was supplied
     */
    private void requireDeclaredVariables(NotificationTemplate template, Map<String, Object> variables) {
        List<String> missing = new ArrayList<>();
        for (String declared : template.getVariables()) {
            if (variables == null || variables.get(declared) == null) {
                missing.add(declared);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Template %s/%s/%s needs %s".formatted(
                            template.getTemplateKey(), template.getChannel(), template.getLocale(), missing));
        }
    }

    /**
     * @param text the template text, or null when the template has no such field
     * @param mode the mode to parse and escape in
     * @param context the variables
     * @return the rendered text, or null if there was none to render
     */
    private String renderField(String text, TemplateMode mode, Context context) {
        if (text == null) {
            return null;
        }
        return engine.process(new TemplateSpec(text, mode), context);
    }
}
