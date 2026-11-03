package com.confluo.notifications.document;

import com.confluo.common.mongo.entity.BaseMongoDocument;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The exact words of one message, frozen against the notification they belong to.
 *
 * <p>Written before its notification row becomes {@code PENDING}, and never written again. A resend
 * sends these strings rather than rendering the template afresh, which is what makes a correction to
 * a template unable to rewrite history: whatever somebody received is still readable here as they
 * received it, however the wording has changed since.</p>
 *
 * <p>Kept here rather than beside the state machine for a reason the dispatcher cares about. Its
 * claim query runs constantly and reads only the state columns, while an HTML body is kilobytes it
 * does not look at until the moment it sends - so the rows it scans stay narrow and the partial
 * index over them stays small.</p>
 *
 * <p>This document is not a resource and has no lifecycle of its own. It extends
 * {@link BaseMongoDocument} anyway, because {@code deletedAt} is how a retention policy will
 * eventually reach it, and a message's words are the part most worth expiring.</p>
 */
@Getter
@Setter
@Document(collection = "rendered_messages")
public class RenderedMessage extends BaseMongoDocument {

    /**
     * The notification this is the content of. Unique: a notification has exactly one set of
     * words, written once, and a second would mean something re-rendered a message already sent.
     */
    @Indexed(unique = true)
    private UUID notificationId;

    /** the rendered subject, or null on a channel without one */
    private String subject;

    /** the rendered plain-text body; always present, and the whole message on SMS */
    private String textBody;

    /** the rendered HTML body, or null when the template had none */
    private String htmlBody;

    /**
     * What the template was filled in with.
     *
     * <p>Kept because the words alone do not explain themselves when something looks wrong: a
     * message that went out with the wrong amount in it is a question about the variables, and
     * without them the only answer available is a guess about what the caller passed.</p>
     */
    private Map<String, Object> variables = new HashMap<>();

    /** which template produced this */
    private String templateKey;

    /** which revision of it, so the wording can be traced even after the template has changed */
    private int templateVersion;

    /** the locale actually rendered, after the fallback chain ran */
    private String locale;
}
