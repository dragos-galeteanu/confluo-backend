// The wording of every message the payments service causes to be sent.
//
// Runs automatically only when the mongo-data volume is EMPTY, exactly like
// docker/postgres/10-create-service-databases.sh. An existing volume will not pick it up, so to
// seed one that already exists, run it by hand - it is idempotent and safe to repeat:
//
//   docker compose exec -T mongo mongosh --quiet --file /docker-entrypoint-initdb.d/10-seed-notification-templates.js
//
// (--file rather than a redirect: fed on stdin, mongosh runs interactively and echoes a prompt
// before every line of output. The path is where this directory is mounted in the container.)
//
// Every write is an upsert with $setOnInsert, so a template that already exists is left completely
// alone. That is the point: this file seeds wording, it does not own it. Once somebody has edited
// the copy - through the templates API or in the database - re-running this changes nothing.
//
// ---------------------------------------------------------------------------------------------
// THESE ARE PLACEHOLDERS. The structure is right and the variables are right; the words are not
// meant to be shipped. Rewrite subject, textBody and htmlBody freely. What NOT to change without
// reading the notes below: `category`, and `variables`.
// ---------------------------------------------------------------------------------------------
//
// `category` is a safety property, not a label. TRANSACTIONAL cannot be switched off by anybody's
// preferences, and that is correct for a receipt: nobody complains about the receipt they did not
// get. Declaring a receipt as MARKETING would make it silently droppable for everyone who ever
// opted out of marketing. The send request has no category field precisely so that a caller cannot
// decide this - the template does.
//
// `variables` is the list NotificationRenderingService refuses to render without. Any name in it
// that arrives null makes the send fail, retry and eventually park. So each template below declares
// ONLY the variables its event cannot omit - amounts, currencies, counts, flags - and reaches
// everything else through a `th:if`, which renders nothing when it is absent. Adding a nullable
// name to `variables` is the one edit here that can stop messages going out: an invoice with no
// number, a refund with no order behind it, or a dunning notice Stripe has stopped retrying are all
// ordinary.
//
// Syntax, which is sharper than it looks. `subject` and `textBody` are rendered in Thymeleaf's TEXT
// mode and must use [(${name})]; the escaping form [[${name}]] is REFUSED by the templates API,
// because it HTML-escapes even in a text message and would send "O&#39;Brien" to a handset.
// `htmlBody` is rendered in HTML mode and uses [[${name}]], which escapes - a variable holding
// somebody's note is an injection into every inbox otherwise.
//
// Dates print as instants (2026-11-04T16:35:33Z) because the engine in
// NotificationTemplateEngineConfig is a plain TemplateEngine with only the standard dialect, so
// #temporals is not available. For friendlier dates, register Thymeleaf's Java8TimeDialect on that
// bean and then #temporals.format(...) works here.

// Not `const db = db.getSiblingDB(...)`: that shadows the shell's own `db` and fails before it is
// assigned. The notifications service's Mongo database is its own, separate from the platform's.
const target = db.getSiblingDB(
    (typeof process !== 'undefined' && process.env && process.env.NOTIFICATIONS_MONGO_DB)
        ? process.env.NOTIFICATIONS_MONGO_DB
        : 'confluo_notifications');

const CLASS = 'com.confluo.notifications.document.NotificationTemplate';
const FOOTER_TRANSACTIONAL = 'You are receiving this because it is a record of money moving on your account.';
const FOOTER_ACCOUNT = 'This is an account notification. You can change which of these you receive in your notification settings.';

const templates = [

    // ------------------------------------------------------------------ invoice.receipt
    {
        templateKey: 'invoice.receipt',
        category: 'TRANSACTIONAL',
        // amountPaid, taxAmount and currency are the only three an InvoicePaidEvent cannot omit.
        // The number, the period and both URLs are all nullable on Stripe's own invoice.
        variables: ['amountPaid', 'taxAmount', 'currency'],
        subject: 'Your receipt[# th:if="${invoiceNumber != null}"] for invoice [(${invoiceNumber})][/]',
        textBody: [
            'Your subscription payment went through - thank you.',
            '',
            'Amount paid: [(${currency})] [(${#numbers.formatDecimal(amountPaid / 100.0, 1, 2)})]',
            '[# th:if="${taxAmount > 0}"]Of which tax: [(${currency})] [(${#numbers.formatDecimal(taxAmount / 100.0, 1, 2)})]',
            '[/][# th:if="${invoiceNumber != null}"]Invoice number: [(${invoiceNumber})]',
            '[/][# th:if="${periodStart != null and periodEnd != null}"]Period billed: [(${periodStart})] to [(${periodEnd})]',
            '[/][# th:if="${hostedInvoiceUrl != null}"]',
            'View this invoice: [(${hostedInvoiceUrl})]',
            '[/][# th:if="${invoicePdf != null}"]Download the PDF: [(${invoicePdf})]',
            '[/]',
            FOOTER_TRANSACTIONAL
        ].join('\n'),
        htmlBody: [
            '<p>Your subscription payment went through &mdash; thank you.</p>',
            '<table role="presentation" cellpadding="6">',
            '  <tr><td><strong>Amount paid</strong></td><td>[[${currency}]] [[${#numbers.formatDecimal(amountPaid / 100.0, 1, 2)}]]</td></tr>',
            '  <tr th:if="${taxAmount > 0}"><td>Of which tax</td><td>[[${currency}]] [[${#numbers.formatDecimal(taxAmount / 100.0, 1, 2)}]]</td></tr>',
            '  <tr th:if="${invoiceNumber != null}"><td>Invoice number</td><td>[[${invoiceNumber}]]</td></tr>',
            '  <tr th:if="${periodStart != null and periodEnd != null}"><td>Period billed</td><td>[[${periodStart}]] to [[${periodEnd}]]</td></tr>',
            '</table>',
            '<p th:if="${hostedInvoiceUrl != null}"><a th:href="${hostedInvoiceUrl}">View this invoice</a></p>',
            '<p th:if="${invoicePdf != null}"><a th:href="${invoicePdf}">Download the PDF</a></p>',
            '<p style="color:#666;font-size:12px">' + FOOTER_TRANSACTIONAL + '</p>'
        ].join('\n')
    },

    // ------------------------------------------------------------------ payment.receipt
    {
        templateKey: 'payment.receipt',
        category: 'TRANSACTIONAL',
        // The order reference is null whenever the payment was not for an order - a subscription
        // invoice, say - so it is reached through a th:if rather than declared.
        variables: ['amount', 'currency'],
        subject: 'Your payment of [(${currency})] [(${#numbers.formatDecimal(amount / 100.0, 1, 2)})]',
        textBody: [
            'We have taken your payment - thank you.',
            '',
            'Amount: [(${currency})] [(${#numbers.formatDecimal(amount / 100.0, 1, 2)})]',
            '[# th:if="${orderReferenceId != null}"]For: [(${orderReferenceType})] [(${orderReferenceId})]',
            '[/]This payment includes any tax due, which is shown on the invoice.',
            '',
            FOOTER_TRANSACTIONAL
        ].join('\n'),
        htmlBody: [
            '<p>We have taken your payment &mdash; thank you.</p>',
            '<table role="presentation" cellpadding="6">',
            '  <tr><td><strong>Amount</strong></td><td>[[${currency}]] [[${#numbers.formatDecimal(amount / 100.0, 1, 2)}]]</td></tr>',
            '  <tr th:if="${orderReferenceId != null}"><td>For</td><td>[[${orderReferenceType}]] [[${orderReferenceId}]]</td></tr>',
            '</table>',
            '<p>This payment includes any tax due, which is shown on the invoice.</p>',
            '<p style="color:#666;font-size:12px">' + FOOTER_TRANSACTIONAL + '</p>'
        ].join('\n')
    },

    // ------------------------------------------------------------------ refund.issued
    {
        templateKey: 'refund.issued',
        category: 'TRANSACTIONAL',
        variables: ['amount', 'currency', 'fullyRefunded'],
        subject: 'Your refund of [(${currency})] [(${#numbers.formatDecimal(amount / 100.0, 1, 2)})] is on its way',
        textBody: [
            '[# th:if="${fullyRefunded}"]We have refunded your payment in full.[/][# th:unless="${fullyRefunded}"]We have refunded part of your payment.[/]',
            '',
            'Refunded: [(${currency})] [(${#numbers.formatDecimal(amount / 100.0, 1, 2)})]',
            '[# th:if="${orderReferenceId != null}"]For: [(${orderReferenceType})] [(${orderReferenceId})]',
            '[/]Your bank decides how long this takes to appear, and it is usually a few working days.',
            'Nothing further is needed from you.',
            '',
            FOOTER_TRANSACTIONAL
        ].join('\n'),
        htmlBody: [
            '<p th:if="${fullyRefunded}">We have refunded your payment in full.</p>',
            '<p th:unless="${fullyRefunded}">We have refunded part of your payment.</p>',
            '<table role="presentation" cellpadding="6">',
            '  <tr><td><strong>Refunded</strong></td><td>[[${currency}]] [[${#numbers.formatDecimal(amount / 100.0, 1, 2)}]]</td></tr>',
            '  <tr th:if="${orderReferenceId != null}"><td>For</td><td>[[${orderReferenceType}]] [[${orderReferenceId}]]</td></tr>',
            '</table>',
            '<p>Your bank decides how long this takes to appear, and it is usually a few working days. Nothing further is needed from you.</p>',
            '<p style="color:#666;font-size:12px">' + FOOTER_TRANSACTIONAL + '</p>'
        ].join('\n')
    },

    // ------------------------------------------------------------------ invoice.payment_failed
    {
        templateKey: 'invoice.payment_failed',
        category: 'TRANSACTIONAL',
        // finalAttempt is computed by the listener and always present; nextPaymentAttempt is null
        // precisely when it is true, which is why only one of the two is declared.
        variables: ['amountDue', 'currency', 'attemptCount', 'finalAttempt'],
        subject: '[# th:if="${finalAttempt}"]Action needed: we could not collect your payment[/][# th:unless="${finalAttempt}"]We could not collect your payment[/]',
        textBody: [
            'Your payment did not go through[# th:if="${attemptCount > 1}"] - this was attempt [(${attemptCount})][/].',
            '',
            'Amount due: [(${currency})] [(${#numbers.formatDecimal(amountDue / 100.0, 1, 2)})]',
            '[# th:if="${invoiceNumber != null}"]Invoice number: [(${invoiceNumber})]',
            '[/]',
            '[# th:if="${finalAttempt}"]We will not try again. Your subscription will end unless this invoice is paid.',
            '[/][# th:unless="${finalAttempt}"]We will try again automatically[# th:if="${nextPaymentAttempt != null}"] on [(${nextPaymentAttempt})][/].',
            '[/]',
            '[# th:if="${hostedInvoiceUrl != null}"]You can pay it now instead: [(${hostedInvoiceUrl})]',
            '[/][# th:if="${hostedInvoiceUrl == null}"]Please check the card on your account.',
            '[/]',
            FOOTER_TRANSACTIONAL
        ].join('\n'),
        htmlBody: [
            '<p>Your payment did not go through<span th:if="${attemptCount > 1}"> &mdash; this was attempt [[${attemptCount}]]</span>.</p>',
            '<table role="presentation" cellpadding="6">',
            '  <tr><td><strong>Amount due</strong></td><td>[[${currency}]] [[${#numbers.formatDecimal(amountDue / 100.0, 1, 2)}]]</td></tr>',
            '  <tr th:if="${invoiceNumber != null}"><td>Invoice number</td><td>[[${invoiceNumber}]]</td></tr>',
            '</table>',
            '<p th:if="${finalAttempt}"><strong>We will not try again.</strong> Your subscription will end unless this invoice is paid.</p>',
            '<p th:unless="${finalAttempt}">We will try again automatically<span th:if="${nextPaymentAttempt != null}"> on [[${nextPaymentAttempt}]]</span>.</p>',
            '<p th:if="${hostedInvoiceUrl != null}"><a th:href="${hostedInvoiceUrl}">Pay this invoice now</a></p>',
            '<p th:if="${hostedInvoiceUrl == null}">Please check the card on your account.</p>',
            '<p style="color:#666;font-size:12px">' + FOOTER_TRANSACTIONAL + '</p>'
        ].join('\n')
    },

    // ------------------------------------------------------------------ subscription.ended
    {
        templateKey: 'subscription.ended',
        // ACCOUNT, not TRANSACTIONAL: no money moved. Somebody who has switched account mail off
        // may legitimately not want this, and a subscription ending is visible in the product.
        category: 'ACCOUNT',
        variables: ['planKey', 'audience', 'unpaid'],
        subject: 'Your [(${planKey})] subscription has ended',
        textBody: [
            '[# th:if="${unpaid}"]Your [(${planKey})] subscription has ended because we could not collect payment for it.',
            '[/][# th:unless="${unpaid}"]Your [(${planKey})] subscription has ended.',
            '[/]',
            'Anything that tier included is no longer active on your account.',
            '[# th:if="${unpaid}"]Subscribing again with a working card will restore it.',
            '[/][# th:unless="${unpaid}"]You can subscribe again whenever you like.',
            '[/]',
            FOOTER_ACCOUNT
        ].join('\n'),
        htmlBody: [
            '<p th:if="${unpaid}">Your <strong>[[${planKey}]]</strong> subscription has ended because we could not collect payment for it.</p>',
            '<p th:unless="${unpaid}">Your <strong>[[${planKey}]]</strong> subscription has ended.</p>',
            '<p>Anything that tier included is no longer active on your account.</p>',
            '<p th:if="${unpaid}">Subscribing again with a working card will restore it.</p>',
            '<p th:unless="${unpaid}">You can subscribe again whenever you like.</p>',
            '<p style="color:#666;font-size:12px">' + FOOTER_ACCOUNT + '</p>'
        ].join('\n')
    },

    // ------------------------------------------------------------------ account.payouts_enabled
    {
        templateKey: 'account.payouts_enabled',
        category: 'ACCOUNT',
        variables: ['state', 'payoutsEnabled'],
        subject: 'You can now be paid',
        textBody: [
            'Stripe has finished checking your details. You can now take payments and receive payouts.',
            '',
            'Money from your sales reaches your bank on Stripe\'s own schedule, which is usually a',
            'couple of working days after each sale.',
            '',
            'Nothing further is needed from you.',
            '',
            FOOTER_ACCOUNT
        ].join('\n'),
        htmlBody: [
            '<p>Stripe has finished checking your details. <strong>You can now take payments and receive payouts.</strong></p>',
            '<p>Money from your sales reaches your bank on Stripe&rsquo;s own schedule, which is usually a couple of working days after each sale.</p>',
            '<p>Nothing further is needed from you.</p>',
            '<p style="color:#666;font-size:12px">' + FOOTER_ACCOUNT + '</p>'
        ].join('\n')
    },

    // ------------------------------------------------------------------ account.requirements_due
    {
        templateKey: 'account.requirements_due',
        category: 'ACCOUNT',
        // requirementsCurrentlyDue and disabledReason are both reached through a th:if: Stripe can
        // restrict an account without naming a reason, and the list is its to extend.
        variables: ['state', 'payoutsEnabled'],
        subject: 'Stripe needs something from you before you can be paid',
        textBody: [
            'Stripe has restricted your account[# th:unless="${payoutsEnabled}"] and payouts to your bank are on hold[/].',
            '[# th:if="${disabledReason != null}"]',
            'Stripe\'s reason: [(${disabledReason})]',
            '[/]',
            '[# th:if="${requirementsCurrentlyDue != null and not #lists.isEmpty(requirementsCurrentlyDue)}"]What Stripe still needs:',
            '[# th:each="requirement : ${requirementsCurrentlyDue}"] - [(${requirement})]',
            '[/][/]',
            'These are Stripe\'s own names for the details it wants. Open the payouts section of your',
            'account to provide them - it will take you to Stripe to finish.',
            '',
            FOOTER_ACCOUNT
        ].join('\n'),
        htmlBody: [
            '<p>Stripe has restricted your account<span th:unless="${payoutsEnabled}"> and payouts to your bank are on hold</span>.</p>',
            '<p th:if="${disabledReason != null}">Stripe&rsquo;s reason: <code>[[${disabledReason}]]</code></p>',
            '<div th:if="${requirementsCurrentlyDue != null and not #lists.isEmpty(requirementsCurrentlyDue)}">',
            '  <p>What Stripe still needs:</p>',
            '  <ul><li th:each="requirement : ${requirementsCurrentlyDue}"><code>[[${requirement}]]</code></li></ul>',
            '</div>',
            '<p>These are Stripe&rsquo;s own names for the details it wants. Open the payouts section of your account to provide them &mdash; it will take you to Stripe to finish.</p>',
            '<p style="color:#666;font-size:12px">' + FOOTER_ACCOUNT + '</p>'
        ].join('\n')
    }
];

const now = new Date();
let inserted = 0;
let left = 0;

for (const template of templates) {
    const scope = {templateKey: template.templateKey, channel: 'EMAIL', locale: 'en'};
    const result = target.notification_templates.updateOne(
        scope,
        {
            $setOnInsert: Object.assign({}, scope, {
                _class: CLASS,
                category: template.category,
                subject: template.subject,
                textBody: template.textBody,
                htmlBody: template.htmlBody,
                variables: template.variables,
                version: 1,
                createdAt: now,
                updatedAt: now,
                // Live. Resolution only ever returns a template whose disabledAt is null, and
                // retiring one is a dated act rather than a delete.
                disabledAt: null,
                deletedAt: null
            })
        },
        {upsert: true});

    if (result.upsertedCount === 1) {
        inserted++;
        print('seeded   ' + template.templateKey);
    } else {
        left++;
        print('kept     ' + template.templateKey + ' (already present, not touched)');
    }
}

// The unique index the document declares. Spring Data creates it on startup because
// auto-index-creation is on, but seeding can run before the service has ever started.
target.notification_templates.createIndex(
    {templateKey: 1, channel: 1, locale: 1},
    {name: 'uq_notification_templates_scope', unique: true});

print('');
print(inserted + ' template(s) seeded, ' + left + ' left as they were, in ' + target.getName());
