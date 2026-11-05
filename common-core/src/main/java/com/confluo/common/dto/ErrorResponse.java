package com.confluo.common.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Error response DTO.
 * @param timestamp timestamp of the error
 * @param status HTTP status code
 * @param error error type
 * @param path path of the request
 * @param message error message
 * @param validationErrors validation errors
 */
public record ErrorResponse(
        Instant timestamp,
        Integer status,
        String error,
        String path,
        String message,
        Map<String, String> validationErrors
) {
}
