package com.erolitechnologies.bizflow.security;

import com.erolitechnologies.bizflow.infra.AppException;

public class PermissionDeniedException extends AppException {
    public PermissionDeniedException(Permission p) {
        super("You do not have permission to do this (" + p.description() + "). Ask an administrator for access.");
    }
}
