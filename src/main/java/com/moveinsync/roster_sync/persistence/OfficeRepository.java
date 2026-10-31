package com.moveinsync.roster_sync.persistence;

import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OfficeRepository {

    private final JdbcClient jdbc;

    public OfficeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> findCodes(String tenantId) {
        return jdbc.sql("SELECT office_code FROM office WHERE tenant_id = :tenantId")
                .param("tenantId", tenantId)
                .query(String.class)
                .set();
    }
}