package com.confluo.common.exception;

import com.confluo.common.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.lang.reflect.Method;
import java.security.Principal;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link GlobalExceptionHandler}. Each handler is called directly: the mapping from an
 * exception to a status and a message is the behaviour, and it needs no servlet container.
 */
class GlobalExceptionHandlerTest {

    private static final String PATH = "/api/widgets";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    GlobalExceptionHandlerTest() {
        given(request.getRequestURI()).willReturn(PATH);
    }

    /** a method to hang a {@link MethodParameter} off, for the exceptions that demand one */
    @SuppressWarnings("unused")
    private void target(String name) {
    }

    private MethodParameter parameter() throws NoSuchMethodException {
        Method method = GlobalExceptionHandlerTest.class.getDeclaredMethod("target", String.class);
        return new MethodParameter(method, 0);
    }

    private static void assertResponse(ResponseEntity<ErrorResponse> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(status.value());
        assertThat(response.getBody().error()).isEqualTo(status.getReasonPhrase());
        assertThat(response.getBody().path()).isEqualTo(PATH);
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    @Test
    void validationFailuresAreReportedFieldByField() throws NoSuchMethodException {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "name", "must not be blank"));
        binding.addError(new FieldError("request", "email", "must be a well-formed email address"));

        ResponseEntity<ErrorResponse> response = handler.handleValidationExceptions(
                new MethodArgumentNotValidException(parameter(), binding), request);

        assertResponse(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("Request validation failed");
        assertThat(response.getBody().validationErrors())
                .containsEntry("name", "must not be blank")
                .containsEntry("email", "must be a well-formed email address");
    }

    @Test
    void accessDeniedNamesTheUserWhenThereIsOne() {
        Principal principal = mock(Principal.class);
        given(principal.getName()).willReturn("user-1");
        given(request.getUserPrincipal()).willReturn(principal);

        ResponseEntity<ErrorResponse> response =
                handler.handleAccessDeniedException(new AccessDeniedException("nope"), request);

        assertResponse(response, HttpStatus.FORBIDDEN);
        assertThat(response.getBody().message()).isEqualTo("Access denied");
        assertThat(response.getBody().validationErrors()).isNull();
    }

    @Test
    void accessDeniedWorksWithoutAPrincipal() {
        given(request.getUserPrincipal()).willReturn(null);

        ResponseEntity<ErrorResponse> response =
                handler.handleAccessDeniedException(new AccessDeniedException("nope"), request);

        assertResponse(response, HttpStatus.FORBIDDEN);
    }

    @Test
    void aMissingResourceKeepsItsOwnMessage() {
        ResponseEntity<ErrorResponse> response = handler.handleResourceNotFoundException(
                new ResourceNotFoundException("Widget 42 not found"), request);

        assertResponse(response, HttpStatus.NOT_FOUND);
        assertThat(response.getBody().message()).isEqualTo("Widget 42 not found");
    }

    @Test
    void anInvalidFilterKeepsItsOwnMessage() {
        ResponseEntity<ErrorResponse> response = handler.handleInvalidFilterException(
                new InvalidFilterException("Unknown filter field 'bogus'"), request);

        assertResponse(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("Unknown filter field 'bogus'");
    }

    @Test
    void aTypeMismatchNamesTheParameterAndTheValue() throws NoSuchMethodException {
        MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
                "not-a-uuid", UUID.class, "id", parameter(), new IllegalArgumentException());

        ResponseEntity<ErrorResponse> response =
                handler.handleMethodArgumentTypeMismatchException(exception, request);

        assertResponse(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("Invalid value 'not-a-uuid' for parameter 'id'");
    }

    @Test
    void aMissingHeaderIsNamed() throws NoSuchMethodException {
        ResponseEntity<ErrorResponse> response = handler.handleMissingRequestHeaderException(
                new MissingRequestHeaderException("X-Tenant", parameter()), request);

        assertResponse(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("Required header 'X-Tenant' is missing");
    }

    @Test
    void aMissingParameterIsNamed() {
        ResponseEntity<ErrorResponse> response = handler.handleMissingServletRequestParameterException(
                new MissingServletRequestParameterException("page", "int"), request);

        assertResponse(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("Required parameter 'page' is missing");
    }

    @Test
    void insufficientTokensArePaymentRequired() {
        ResponseEntity<ErrorResponse> response = handler.handleInsufficientTokensException(
                new InsufficientTokensException("Not enough tokens"), request);

        assertResponse(response, HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody().message()).isEqualTo("Not enough tokens");
    }

    @Test
    void tooManyRequestsIsRateLimited() {
        ResponseEntity<ErrorResponse> response = handler.handleTooManyRequestsException(
                new TooManyRequestsException("Slow down"), request);

        assertResponse(response, HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().message()).isEqualTo("Slow down");
    }

    @Test
    void aBusinessRuleViolationIsUnprocessable() {
        ResponseEntity<ErrorResponse> response = handler.handleIllegalStateException(
                new IllegalStateException("Widget is already shipped"), request);

        assertResponse(response, HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().message()).isEqualTo("Widget is already shipped");
    }

    @Test
    void aConstraintViolationIsReportedWithoutLeakingTheDatabaseMessage() {
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "could not execute statement", new SQLException("duplicate key value violates unique constraint"));

        ResponseEntity<ErrorResponse> response =
                handler.handleDataIntegrityViolationException(exception, request);

        assertResponse(response, HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().message()).isEqualTo("The request conflicts with data that already exists");
        assertThat(response.getBody().message()).doesNotContain("unique constraint");
    }

    @Test
    void anUnmappedUrlIsNotFound() {
        ResponseEntity<ErrorResponse> response = handler.handleNoResourceFoundException(
                new NoResourceFoundException(org.springframework.http.HttpMethod.GET, PATH), request);

        assertResponse(response, HttpStatus.NOT_FOUND);
        assertThat(response.getBody().message()).isEqualTo("No resource found at " + PATH);
    }

    @Test
    void anythingElseIsAnInternalErrorThatSaysNothingAboutTheCause() {
        ResponseEntity<ErrorResponse> response = handler.handleGlobalException(
                new RuntimeException("connection pool exhausted at 10.0.0.5:5432"), request);

        assertResponse(response, HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().message())
                .isEqualTo("An unexpected error occurred. If this persists, please contact support.");
        assertThat(response.getBody().message()).doesNotContain("10.0.0.5");
    }
}
