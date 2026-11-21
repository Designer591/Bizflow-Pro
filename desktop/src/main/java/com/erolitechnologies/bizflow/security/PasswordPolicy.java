package com.erolitechnologies.bizflow.security;

import com.erolitechnologies.bizflow.infra.AppException;

public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static void check(String password) {
        if (password == null || password.length() < 8)
            throw new AppException("Password must be at least 8 characters long.");
        boolean letter = password.chars().anyMatch(Character::isLetter);
        boolean digit = password.chars().anyMatch(Character::isDigit);
        if (!letter || !digit) throw new AppException("Password must contain at least one letter and one number.");
        if (password.length() > 72) throw new AppException("Password is too long (maximum 72 characters).");
    }
}
