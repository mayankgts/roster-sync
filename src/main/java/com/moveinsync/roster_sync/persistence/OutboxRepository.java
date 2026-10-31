package com.moveinsync.roster_sync.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public OutboxRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void append(String tenantId, String employeeId, String eventType, long sourceVersion, Object payload) {
        jdbc.sql("""
                INSERT INTO outbox (tenant_id, employee_id, event_type, source_version, payload)
                VALUES (:tenantId, :employeeId, :eventType, :sourceVersion, CAST(:payload AS jsonb))
                """)
                .param("tenantId", tenantId)
                .param("employeeId", employeeId)
                .param("eventType", eventType)
                .param("sourceVersion", sourceVersion)
                .param("payload", toJson(payload))
                .update();
    }

    private String toJson(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise outbox payload", e);
        }
    }
}