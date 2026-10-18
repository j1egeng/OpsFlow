package com.opsflow.common;

import java.time.Instant;
import java.util.Map;

public record ApiError(int status, String code, String message, String path,
                       Map<String, String> fields, Instant timestamp) {
    public static ApiError of(int status, String code, String message, String path) {
        return new ApiError(status, code, message, path, Map.of(), Instant.now());
    }
}
