package com.moveinsync.roster_sync.persistence;

import com.moveinsync.roster_sync.domain.Employee;
import com.moveinsync.roster_sync.domain.EmployeeStatus;
import com.moveinsync.roster_sync.domain.HrFields;
import com.moveinsync.roster_sync.domain.RecordHasher;
import com.moveinsync.roster_sync.domain.TransportFields;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EmployeeRepository {

    private final JdbcClient jdbc;

    public EmployeeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Blocks until no other transaction holds this employee's lock, then holds it
     * until the current transaction commits or rolls back. Works even if the
     * employee row does not exist yet.
     */
    public void lock(String tenantId, String employeeId) {
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", tenantId + ":" + employeeId)
                .query(Integer.class)
                .single();
    }

    public Optional<Employee> find(String tenantId, String employeeId) {
        return jdbc.sql("SELECT * FROM employee WHERE tenant_id = :tenantId AND employee_id = :employeeId")
                .param("tenantId", tenantId)
                .param("employeeId", employeeId)
                .query(EmployeeRepository::mapRow)
                .optional();
    }

    /**
     * Inserts or updates HR-owned columns only. Transport-owned columns are not in
     * the UPDATE list, so this statement cannot overwrite them. The WHERE clause
     * is a second guard: the database itself refuses to move a version backwards.
     * record_hash is recomputed on every HR write so nightly reconciliation can
     * compare fingerprints instead of full rows.
     */
    public void saveHrState(Employee e) {
        jdbc.sql("""
                INSERT INTO employee (tenant_id, employee_id, status, hr_version,
                                      name, email, phone, manager_id, office_code, shift_code,
                                      record_hash)
                VALUES (:tenantId, :employeeId, :status, :hrVersion,
                        :name, :email, :phone, :managerId, :officeCode, :shiftCode,
                        :recordHash)
                ON CONFLICT (tenant_id, employee_id) DO UPDATE SET
                    status      = EXCLUDED.status,
                    hr_version  = EXCLUDED.hr_version,
                    name        = EXCLUDED.name,
                    email       = EXCLUDED.email,
                    phone       = EXCLUDED.phone,
                    manager_id  = EXCLUDED.manager_id,
                    office_code = EXCLUDED.office_code,
                    shift_code  = EXCLUDED.shift_code,
                    record_hash = EXCLUDED.record_hash,
                    updated_at  = now()
                WHERE employee.hr_version < EXCLUDED.hr_version
                """)
                .param("tenantId", e.tenantId())
                .param("employeeId", e.employeeId())
                .param("status", e.status().name())
                .param("hrVersion", e.hrVersion())
                .param("name", e.hr().name())
                .param("email", e.hr().email())
                .param("phone", e.hr().phone())
                .param("managerId", e.hr().managerId())
                .param("officeCode", e.hr().officeCode())
                .param("shiftCode", e.hr().shiftCode())
                .param("recordHash", RecordHasher.hash(e.employeeId(), e.hrVersion(), e.hr()))
                .update();
    }

    /** Writes transport-owned columns only. HR columns, hr_version and record_hash are untouched. */
    public void saveTransportState(String tenantId, String employeeId, TransportFields transport, long adminVersion) {
        jdbc.sql("""
                UPDATE employee
                   SET pickup_point = :pickupPoint,
                       special_assistance = :specialAssistance,
                       admin_version = :adminVersion,
                       updated_at = now()
                 WHERE tenant_id = :tenantId AND employee_id = :employeeId
                """)
                .param("pickupPoint", transport.pickupPoint())
                .param("specialAssistance", transport.specialAssistance())
                .param("adminVersion", adminVersion)
                .param("tenantId", tenantId)
                .param("employeeId", employeeId)
                .update();
    }

    /**
     * Deactivates only if the row is still exactly what reconciliation saw. If HR changed
     * the employee in the meantime, the hash differs and nothing happens.
     */
    public int deactivateIfUnchanged(String tenantId, String employeeId, Long expectedHash) {
        return jdbc.sql("""
                UPDATE employee
                   SET status = 'INACTIVE', updated_at = now()
                 WHERE tenant_id = :tenantId
                   AND employee_id = :employeeId
                   AND status = 'ACTIVE'
                   AND record_hash IS NOT DISTINCT FROM CAST(:expectedHash AS BIGINT)
                """)
                .param("tenantId", tenantId)
                .param("employeeId", employeeId)
                .param("expectedHash", expectedHash)
                .update();
    }

    private static Employee mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Employee(
                rs.getString("tenant_id"),
                rs.getString("employee_id"),
                EmployeeStatus.valueOf(rs.getString("status")),
                rs.getLong("hr_version"),
                new HrFields(
                        rs.getString("name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("manager_id"),
                        rs.getString("office_code"),
                        rs.getString("shift_code")),
                new TransportFields(
                        rs.getString("pickup_point"),
                        rs.getString("special_assistance")),
                rs.getLong("admin_version"));
    }
}