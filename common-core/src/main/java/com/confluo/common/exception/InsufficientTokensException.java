package com.confluo.common.exception;

/**
 * Exception thrown when there are insufficient tokens available for performing an operation
 */
public class InsufficientTokensException extends RuntimeException {

    public InsufficientTokensException() {
        super();
    }

    public InsufficientTokensException(String message) {
        super(message);
    }
}
