package com.erolitechnologies.bizflow.infra;

/** An error whose message is safe and meaningful to show to a business user. */
public class AppException extends RuntimeException {
    public AppException(String userMessage) { super(userMessage); }
    public AppException(String userMessage, Throwable cause) { super(userMessage, cause); }
}
