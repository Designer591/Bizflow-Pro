package com.erolitechnologies.bizflow.security;

import com.erolitechnologies.bizflow.infra.AppException;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** The signed-in user. Held in memory only; expires after a period of inactivity. */
public final class Session {
    private final long userId;
    private final String username;
    private final String fullName;
    private final long branchId;
    private final Set<String> roles;
    private final Set<Permission> permissions;
    private final Duration idleTimeout;
    private volatile Instant lastActivity;
    private volatile boolean ended;

    public Session(long userId, String username, String fullName, long branchId, Set<String> roles,
                   Set<Permission> permissions, Duration idleTimeout) {
        this.userId = userId;
        this.username = username;
        this.fullName = fullName;
        this.branchId = branchId;
        this.roles = Set.copyOf(roles);
        this.permissions = Set.copyOf(permissions);
        this.idleTimeout = idleTimeout;
        this.lastActivity = Instant.now();
    }

    public long userId() { return userId; }
    public String username() { return username; }
    public String fullName() { return fullName; }
    public long branchId() { return branchId; }
    public Set<String> roles() { return roles; }
    public Set<Permission> permissions() { return permissions; }

    public boolean has(Permission p) { return !isExpired() && permissions.contains(p); }

    /** Throws if the session ended/expired or lacks the permission. Also counts as activity. */
    public void require(Permission p) {
        if (isExpired()) throw new AppException("Your session has ended. Please sign in again.");
        if (!permissions.contains(p)) throw new PermissionDeniedException(p);
        touch();
    }

    public void touch() { lastActivity = Instant.now(); }

    public boolean isExpired() {
        return ended || Duration.between(lastActivity, Instant.now()).compareTo(idleTimeout) > 0;
    }

    public void end() { ended = true; }
}
