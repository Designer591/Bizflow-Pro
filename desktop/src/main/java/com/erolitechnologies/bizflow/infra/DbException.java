package com.erolitechnologies.bizflow.infra;

/** Wraps SQL failures; message is user-safe, cause (kept for logs) carries the technical detail. */
public class DbException extends AppException {
    public DbException(String userMessage, Throwable cause) { super(userMessage, cause); }
}
