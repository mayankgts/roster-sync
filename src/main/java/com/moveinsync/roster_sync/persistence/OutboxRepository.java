package com.moveinsync.roster_sync.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void append(String tenantId, String employeeId, String eventType, long sourceVersion, String payloadJson) {
        jdbc.sql("""
                INSERT INTO outbox (tenant_id, employee_id, event_type, source_version, payload)
                VALUES (:tenantId, :employeeId, :eventType, :sourceVersion, CAST(:payload AS jsonb))
                """)
                .param("tenantId", tenantId)
                .param("employeeId", employeeId)
                .param("eventType", eventType)
                .param("sourceVersion", sourceVersion)
                .param("payload", payloadJson)
                .update();
    }
}