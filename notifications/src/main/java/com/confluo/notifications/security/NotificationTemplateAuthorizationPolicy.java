package com.confluo.notifications.security;

import com.confluo.common.filter.PaginationFilter;
import com.confluo.common.security.BaseAuthorizationPolicy;
import com.confluo.notifications.dto.template.NotificationTemplateCreateRequest;
import com.confluo.notifications.dto.template.NotificationTemplateUpdateRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;


/**
 * Who may read and change the wording of messages.
 *
 * <p>Writing is Admin only, and that is the whole point of these being editable without a deploy:
 * a template is the text of something sent to every customer, under the platform's name, so the
 * ability to change it is the ability to say anything to anyone. It should be as narrow as changing
 * reference data.</p>
 *
 * <p>Reading is Admin or Support - "what exactly does that email say" is a support question.</p>
 *
 * <p>{@link #canResolve} has no counterpart on the interface, because the question it guards has no
 * counterpart among the usual routes: it is not "may this caller read a template" but "may this
 * caller ask which template a send would choose". Everything else is the interface's own method,
 * because none of the rules here depends on which template is being acted on - authorization turns
 * on the caller's role alone.</p>
 *
 * <p>The policy is keyed by {@code String}, not {@code UUID}, because that is what a template's
 * routes carry: the 24-character hex of its {@code ObjectId}, a document deliberately standing
 * outside the {@code UUID}-keyed stack as {@code BaseEntity}'s own documentation explains. Saying
 * so here is what lets {@code canGetById} and {@code canDelete} be the interface's own methods
 * rather than inventions of this class - and it is not cosmetic. A {@code @PreAuthorize} expression
 * is resolved at request time, so passing a hex string to a method declaring a {@code UUID} is not
 * a compile error: Spring converts it, and every call to that route fails inside
 * {@code UUID.fromString}.</p>
 */
@Component("templatePolicy")
public class NotificationTemplateAuthorizationPolicy
        implements BaseAuthorizationPolicy<NotificationTemplateCreateRequest, NotificationTemplateUpdateRequest, String> {

    /**
     * Whether the caller may ask which wording a send would resolve to.
     *
     * <p>Guards the one route here with no equivalent among the inherited ones. The others reach a
     * template by its identifier or list them by a filter; this one asks the service to run the
     * locale fallback chain and report which template wins - the question behind "why was this
     * customer written to in English", answered by the code that decides it rather than by reading
     * the rules and guessing.</p>
     *
     * <p>Admin or Support, the same as reading a template directly, because that is what it is: a
     * template, selected differently. It takes the caller alone because a resolution is asked for
     * by key, channel and locale - there is no identifier to pass, which is also why it needs a
     * name of its own rather than an overload of {@code canGetById}.</p>
     *
     * @param authentication the authentication object
     * @return true if the caller may ask
     */
    public boolean canResolve(Authentication authentication) {
        return isAdminOrSupport(authentication);
    }

    @Override
    public boolean canCreate(Authentication authentication, NotificationTemplateCreateRequest request) {
        return isAdmin(authentication);
    }

    @Override
    public boolean canGetById(Authentication authentication, String id) {
        return isAdminOrSupport(authentication);
    }

    @Override
    public boolean canGetAll(Authentication authentication, PaginationFilter filter) {
        return isAdminOrSupport(authentication);
    }

    @Override
    public boolean canUpdate(Authentication authentication, NotificationTemplateUpdateRequest request) {
        return isAdmin(authentication);
    }

    @Override
    public boolean canDelete(Authentication authentication, String id) {
        return isAdmin(authentication);
    }

    @Override
    public boolean canDisable(Authentication authentication) {
        return isAdmin(authentication);
    }
}
