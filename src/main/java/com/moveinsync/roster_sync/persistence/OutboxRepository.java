package com.moveinsync.roster_sync.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.moveinsync.roster_sync.outbox.OutboxEvent;
import java.util.List;

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

        /**
     * Locks up to `limit` pending rows for this transaction. SKIP LOCKED means a second
     * relay instance gets different rows instead of waiting or publishing duplicates.
     */
    public List<OutboxEvent> claimPending(int limit) {
        return jdbc.sql("""
                SELECT id, tenant_id, employee_id, event_type, source_version, payload::text AS payload
                  FROM outbox
                 WHERE published_at IS NULL AND dead_lettered_at IS NULL
                 ORDER BY id
                 LIMIT :limit
                   FOR UPDATE SKIP LOCKED
                """)
                .param("limit", limit)
                .query((rs, rowNum) -> new OutboxEvent(
                        rs.getLong("id"),
                        rs.getString("tenant_id"),
                        rs.getString("employee_id"),
                        rs.getString("event_type"),
                        rs.getLong("source_version"),
                        rs.getString("payload")))
                .list();
    }

    public void markPublished(long id) {
        jdbc.sql("UPDATE outbox SET published_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }

    /** Counts a failed attempt; dead-letters the row once it reaches maxAttempts. */
    public void recordFailure(long id, String errorType, int maxAttempts) {
        jdbc.sql("""
                UPDATE outbox
                   SET attempts = attempts + 1,
                       last_error = :errorType,
                       dead_lettered_at = CASE WHEN attempts + 1 >= :maxAttempts THEN now() END
                 WHERE id = :id
                """)
                .param("errorType", errorType)
                .param("maxAttempts", maxAttempts)
                .param("id", id)
                .update();
    }
}