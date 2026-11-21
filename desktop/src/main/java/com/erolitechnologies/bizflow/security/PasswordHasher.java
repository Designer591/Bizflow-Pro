package com.erolitechnologies.bizflow.security;

public interface PasswordHasher {
    String hash(String password);
    boolean verify(String password, String hash);
}
