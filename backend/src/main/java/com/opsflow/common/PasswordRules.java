package com.opsflow.common;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;

public final class PasswordRules {
    private PasswordRules() {
    }

    public static void validateNewPassword(String password) {
        if (password == null || password.isBlank() || password.length() < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD",
                    "密码至少 12 个字符，且 UTF-8 编码后不能超过 72 字节");
        }
    }
}
