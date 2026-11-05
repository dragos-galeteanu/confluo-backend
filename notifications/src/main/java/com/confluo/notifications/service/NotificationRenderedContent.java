package com.confluo.notifications.service;

/**
 * What a template turned into, once its variables were filled in.
 *
 * <p>Frozen from here on. The pipeline writes this against the notification it belongs to, and a
 * resend sends exactly these strings rather than rendering again - so a correction to a template
 * never rewrites history, and a message already sent can always be read back as it was.</p>
 *
 * @param subject the email subject, or null on a channel without one
 * @param textBody the plain-text body; always present, and the whole message on SMS
 * @param htmlBody the HTML body, or null when the template has none
 */
public record NotificationRenderedContent(String subject, String textBody, String htmlBody) {
}
