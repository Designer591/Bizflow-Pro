package com.erolitechnologies.bizflow.service;

import com.erolitechnologies.bizflow.infra.Db;
import com.erolitechnologies.bizflow.security.Permission;
import com.erolitechnologies.bizflow.security.Session;

import java.util.List;

/** Employee profiles (HR details) linked to login accounts. Accounts/roles are managed by UserService. */
public final class EmployeeService {
    public record Employee(long id, Long userId, String fullName, String username, String role, String jobTitle, String nationalId,
                           String phone, String email, String hiredOn, boolean active, String branch) {}

    public record Activity(String when, String action, String entity, String details) {}

    private final Db db;

    public EmployeeService(Db db) { this.db = db; }

    public List<Employee> list(Session s) {
        s.require(Permission.EMPLOYEE_MANAGE);
        return db.read(c -> Db.query(c,
                "SELECT e.id, e.user_id, e.full_name, COALESCE(u.username,''), COALESCE((SELECT ro.name FROM user_roles ur JOIN roles ro ON ro.id = ur.role_id WHERE ur.user_id = u.id LIMIT 1),''), " +
                "COALESCE(e.job_title,''), COALESCE(e.national_id,''), COALESCE(e.phone,''), COALESCE(e.email,''), COALESCE(e.hired_on,''), " +
                "CASE WHEN u.id IS NULL THEN e.active ELSE u.active END, COALESCE(b.name,'') FROM employees e LEFT JOIN users u ON u.id = e.user_id LEFT JOIN branches b ON b.id = e.branch_id ORDER BY e.full_name",
                rs -> new Employee(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getString(10), rs.getInt(11) == 1, rs.getString(12))));
    }

    public void updateProfile(Session s, long employeeId, String jobTitle, String nationalId, String hiredOn) {
        s.require(Permission.EMPLOYEE_MANAGE);
        if (hiredOn != null && !hiredOn.isBlank() && !hiredOn.matches("\\d{4}-\\d{2}-\\d{2}"))
            throw new com.erolitechnologies.bizflow.infra.AppException("Hire date must look like 2026-03-15.");
        db.txVoid(c -> {
            Db.update(c, "UPDATE employees SET job_title = ?, national_id = ?, hired_on = ? WHERE id = ?", Validate.optional(jobTitle), Validate.optional(nationalId), Validate.optional(hiredOn), employeeId);
            AuditService.log(c, s, "EMPLOYEE_UPDATED", "employee", employeeId, null);
            return null;
        });
    }

    /** Review of what an employee did in the system (administrators/managers only). */
    public List<Activity> activity(Session s, long userId, int limit) {
        s.require(Permission.EMPLOYEE_MANAGE);
        return db.read(c -> Db.query(c, "SELECT created_at, action, COALESCE(entity,''), COALESCE(details,'') FROM audit_logs WHERE user_id = ? ORDER BY id DESC LIMIT " + Math.max(1, Math.min(limit, 2000)),
                rs -> new Activity(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), userId));
    }
}
