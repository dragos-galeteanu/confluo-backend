package com.confluo.common.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the domain exceptions themselves: each carries a message to the client through
 * {@link GlobalExceptionHandler}, and each is unchecked so services need not declare it.
 */
class DomainExceptionsTest {

    @Test
    void eachExceptionCarriesItsMessage() {
        assertThat(new ResourceNotFoundException("gone").getMessage()).isEqualTo("gone");
        assertThat(new InvalidFilterException("bad filter").getMessage()).isEqualTo("bad filter");
        assertThat(new InsufficientTokensException("no tokens").getMessage()).isEqualTo("no tokens");
        assertThat(new TooManyRequestsException("slow down").getMessage()).isEqualTo("slow down");
    }

    @Test
    void theExceptionsThatAllowItCanBeThrownWithoutAMessage() {
        assertThat(new ResourceNotFoundException().getMessage()).isNull();
        assertThat(new InvalidFilterException().getMessage()).isNull();
        assertThat(new InsufficientTokensException().getMessage()).isNull();
    }

    @Test
    void allOfThemAreUnchecked() {
        assertThat(new ResourceNotFoundException("gone")).isInstanceOf(RuntimeException.class);
        assertThat(new InvalidFilterException("bad filter")).isInstanceOf(RuntimeException.class);
        assertThat(new InsufficientTokensException("no tokens")).isInstanceOf(RuntimeException.class);
        assertThat(new TooManyRequestsException("slow down")).isInstanceOf(RuntimeException.class);
    }
}
