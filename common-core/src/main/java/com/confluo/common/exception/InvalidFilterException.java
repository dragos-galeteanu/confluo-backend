package com.confluo.common.exception;

/**
 * Exception thrown when an invalid filter is provided.
 */
public class InvalidFilterException extends RuntimeException {

    public InvalidFilterException() {
        super();
    }

    public InvalidFilterException(String message) {
        super(message);
    }
}
