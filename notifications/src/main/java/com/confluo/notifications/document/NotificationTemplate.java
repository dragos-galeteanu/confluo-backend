package com.confluo.notifications.document;

import com.confluo.common.mongo.entity.BaseMongoDocument;
import com.confluo.notifications.channel.Category;
import com.confluo.notifications.channel.Channel;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * The wording of one kind of message, in one language, for one channel.
 *
 * <p>A document rather than a row, and in MongoDB rather than beside the state machine, because
 * this is the part that changes without a deploy: the copy of every message the platform sends,
 * edited by whoever writes the copy. A body is also a shape that differs per channel - a text
 * message has no subject and no HTML - and encoding that in columns means a migration every time a
 * channel is added.</p>
 *
 * <p><strong>One document per key, channel and locale,</strong> enforced by the compound unique
 * index below, and edited in place. {@link #version} is a revision counter bumped on each edit
 * rather than a history: what was actually sent is answered by the rendered message frozen against
 * each notification, which is the only version anyone ever needs to read back. Keeping old
 * revisions of the template as well would be a second answer to the same question.</p>
 *
 * <p>A template is <em>live</em> while its {@code disabledAt} is null - the same meaning
 * {@code disabledAt} carries everywhere else, and the same way {@code FeatureFlag} uses it. So
 * retiring a template is a dated act that can be read back, and is not the same as deleting it.
 * Resolution only ever returns a live one.</p>
 *
 * <p>{@link #textBody} is required on every channel and is not an afterthought for email. A plain
 * part beside the HTML one is what keeps a message out of a spam folder and readable in a watch
 * notification, so the two bodies are separate fields rather than one body and a format: there is
 * no way to express "HTML only" and therefore no way to forget.</p>
 *
 * <p><strong>Which inline syntax a field takes is not a style question.</strong> Thymeleaf's
 * {@code [[${name}]]} is escaped output and {@code [(${name})]} is unescaped, and the escaping is
 * HTML escaping <em>whatever the template mode is</em>. So:</p>
 *
 * <ul>
 *   <li>{@link #subject} and {@link #textBody} use <strong>{@code [(${name})]}</strong>. The other
 *       form would send {@code Ben &amp;amp; Jerry} to a handset, and only for the names that
 *       contain an ampersand, an angle bracket or an apostrophe - so it passes every test written
 *       with a tidy name in it. The service refuses it rather than letting it reach anyone.</li>
 *   <li>{@link #htmlBody} uses <strong>{@code [[${name}]]}</strong>, which escapes. A variable here
 *       holds somebody's name or somebody's note, and an unescaped one is an injection into every
 *       inbox the message reaches. The unescaped form is allowed, for markup assembled elsewhere,
 *       and logged as a warning so it is never there by accident.</li>
 * </ul>
 *
 * <p>Both forms are evaluated as expressions, which is the other reason writing a template is an
 * administrator's privilege: a template is executable, not just text.</p>
 */
@Getter
@Setter
@Document(collection = "notification_templates")
@CompoundIndex(
        name = "uq_notification_templates_scope",
        def = "{'templateKey': 1, 'channel': 1, 'locale': 1}",
        unique = true)
public class NotificationTemplate extends BaseMongoDocument {

    /** what the message is, e.g. {@code welcome} or {@code password-reset} */
    private String templateKey;

    /** the channel this wording is for */
    private Channel channel;

    /**
     * The language tag, lowercased - {@code en}, {@code ro}, {@code ro-ro}. Lowercased because
     * resolution matches it exactly, and a locale stored in one spelling and asked for in another
     * is a template that silently falls back to English.
     */
    private String locale;

    /**
     * What kind of message this is, and so whether a preference could stop it.
     *
     * <p>Declared here rather than passed on each send, because "a welcome email is an account
     * message" is a property of the message and not of the moment. A caller cannot upgrade their
     * own marketing into something nobody may decline.</p>
     */
    private Category category;

    /** the email subject, rendered like any other field; null on a channel that has no subject */
    private String subject;

    /** the plain-text body. Required on every channel: it is the whole message on SMS */
    private String textBody;

    /** the HTML body, for email only; null leaves a message as text alone */
    private String htmlBody;

    /**
     * The variables this template expects.
     *
     * <p>Declared so that a render can be refused when one is missing, instead of producing a
     * message with a blank where somebody's name should be. It is the template's own statement
     * about what it needs, which is why it is stored next to the body rather than inferred by
     * parsing it.</p>
     */
    private List<String> variables = new ArrayList<>();

    /** the revision, from 1, bumped on each edit; recorded against every message rendered from it */
    private int version = 1;
}
