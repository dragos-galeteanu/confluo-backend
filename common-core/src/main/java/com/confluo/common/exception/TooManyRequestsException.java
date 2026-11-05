package com.confluo.common.exception;

/**
 * Exception thrown when the user has made too many requests in a short period of time.
 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException(String message) {
        super(message);
    }
}
