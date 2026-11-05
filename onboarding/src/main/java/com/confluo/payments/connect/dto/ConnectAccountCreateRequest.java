package com.confluo.payments.connect.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Start a seller's onboarding.
 *
 * <p>Deliberately short. Stripe collects the rest - the legal name, the date of birth, the bank
 * account, the identity document - on its own hosted pages, and that is the point of using them:
 * none of it passes through this platform, so none of it has to be stored, secured or deleted
 * here.</p>
 *
 * <p>{@code country} is the one field that cannot be changed afterwards. Stripe fixes an account's
 * country at creation because it decides which requirements apply and which currencies the account
 * can be paid in; a seller who moves country needs a new account.</p>
 *
 * @param firebaseUid whose account it is
 * @param country where the seller is, as an uppercase ISO-3166 alpha-2 code. Immutable once set
 * @param defaultCurrency what Stripe should settle them in, uppercase ISO 4217. Must be one the
 *                        chosen country supports, which Stripe decides
 * @param email where Stripe may write to them about their account, or null. Stripe asks for one
 *              during onboarding if it is not given; supplying it saves them a field
 */
public record ConnectAccountCreateRequest(

        @NotBlank
        @Size(max = 128)
        String firebaseUid,

        @NotBlank
        @Pattern(regexp = "^[A-Z]{2}$", message = "must be an uppercase ISO-3166 alpha-2 code")
        String country,

        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be an uppercase ISO 4217 code")
        String defaultCurrency,

        @Email
        @Size(max = 320)
        String email
) {
}
