package com.erolitechnologies.bizflow.security;

import at.favre.lib.crypto.bcrypt.BCrypt;

public final class BcryptPasswordHasher implements PasswordHasher {
    private final int cost;

    public BcryptPasswordHasher() { this(12); }
    public BcryptPasswordHasher(int cost) { this.cost = cost; }

    @Override public String hash(String password) {
        return BCrypt.withDefaults().hashToString(cost, password.toCharArray());
    }

    @Override public boolean verify(String password, String hash) {
        if (password == null || hash == null) return false;
        return BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
    }
}
