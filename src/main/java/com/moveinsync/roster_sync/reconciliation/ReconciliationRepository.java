package com.moveinsync.roster_sync.reconciliation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.BiConsumer;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class ReconciliationRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate streaming;
    private final TransactionTemplate readOnlyTx;
    private final ObjectMapper json;

    public ReconciliationRepository(JdbcClient jdbc, DataSource dataSource,
                                    PlatformTransactionManager txManager, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.streaming = new JdbcTemplate(dataSource);
        this.streaming.setFetchSize(5_000);
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
    }

    /**
     * Streams (employeeId, recordHash) for every ACTIVE employee of a tenant.
     * The Postgres driver only streams with a fetch size INSIDE a transaction;
     * otherwise it loads the whole result into memory first.
     */
    public void forEachActiveHash(String tenantId, BiConsumer<String, Long> consumer) {
        readOnlyTx.executeWithoutResult(status -> streaming.query(
                "SELECT employee_id, record_hash FROM employee WHERE tenant_id = ? AND status = 'ACTIVE'",
                (RowCallbackHandler) rs -> consumer.accept(
                        rs.getString("employee_id"), rs.getObject("record_hash", Long.class)),
                tenantId));
    }

    public long createRun(String tenantId, String filePath) {
        return jdbc.sql("""
                INSERT INTO reconciliation_run (tenant_id, status, file_path)
                VALUES (:tenantId, 'RUNNING', :filePath)
                RETURNING id
                """)
                .param("tenantId", tenantId)
                .param("filePath", filePath)
                .query(Long.class)
                .single();
    }

    public void finish(long runId, String status, ReconciliationSummary summary) {
        jdbc.sql("""
                UPDATE reconciliation_run
                   SET status = :status, summary = CAST(:summary AS jsonb), finished_at = now()
                 WHERE id = :id
                """)
                .param("status", status)
                .param("summary", toJson(summary))
                .param("id", runId)
                .update();
    }

    /** Atomically moves BLOCKED -> RUNNING. Returns false if the run was not BLOCKED. */
    public boolean markApproved(String tenantId, long runId, String approvedBy) {
        return jdbc.sql("""
                UPDATE reconciliation_run
                   SET status = 'RUNNING', approved_by = :approvedBy, approved_at = now()
                 WHERE id = :id AND tenant_id = :tenantId AND status = 'BLOCKED'
                """)
                .param("approvedBy", approvedBy)
                .param("id", runId)
                .param("tenantId", tenantId)
                .update() == 1;
    }

    public Optional<ReconciliationRun> find(String tenantId, long runId) {
        return jdbc.sql("""
                SELECT id, tenant_id, status, file_path, summary::text AS summary,
                       approved_by, created_at, finished_at
                  FROM reconciliation_run
                 WHERE id = :id AND tenant_id = :tenantId
                """)
                .param("id", runId)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new ReconciliationRun(
                        rs.getLong("id"),
                        rs.getString("tenant_id"),
                        rs.getString("status"),
                        rs.getString("file_path"),
                        fromJson(rs.getString("summary")),
                        rs.getString("approved_by"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("finished_at", OffsetDateTime.class)))
                .optional();
    }

    private String toJson(ReconciliationSummary summary) {
        try {
            return json.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise summary", e);
        }
    }

    private ReconciliationSummary fromJson(String value) {
        if (value == null) {
            return null;
        }
        try {
            return json.readValue(value, ReconciliationSummary.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read summary", e);
        }
    }
}