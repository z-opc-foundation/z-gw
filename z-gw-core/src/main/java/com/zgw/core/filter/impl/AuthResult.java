package com.zgw.core.filter.impl;

public class AuthResult {
    private final boolean success;
    private final String userId;
    private final String[] roles;
    private final String message;

    private AuthResult(boolean success, String userId, String[] roles, String message) {
        this.success = success;
        this.userId = userId;
        this.roles = roles;
        this.message = message;
    }

    public static AuthResult success(String userId, String[] roles) {
        return new AuthResult(true, userId, roles, null);
    }

    public static AuthResult failure(String message) {
        return new AuthResult(false, null, null, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getUserId() {
        return userId;
    }

    public String[] getRoles() {
        return roles;
    }

    public String getMessage() {
        return message;
    }
}