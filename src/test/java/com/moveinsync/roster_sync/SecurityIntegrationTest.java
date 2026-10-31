package com.moveinsync.roster_sync;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moveinsync.roster_sync.domain.IncomingRecord;
import com.moveinsync.roster_sync.security.TokenService;
import com.moveinsync.roster_sync.service.IngestService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "roster.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityIntegrationTest {

    private static final String BATCH = """
            {"records":[{"employeeId":"E1003","type":"CREATE","version":1,
              "name":"Test User","email":"e1003@acme.com","officeCode":"BLR01"}]}
            """;

    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;
    @Autowired IngestService ingestService;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE employee, outbox RESTART IDENTITY CASCADE").update();
        ingestService.ingest("acme", List.of(employee("E1001"), employee("E1002")));
    }

    @Test
    void requestWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/tenants/acme/employees/E1001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = token("admin-acme", "admin-acme-pass");
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

        mvc.perform(get("/api/v1/tenants/acme/employees/E1001").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void integrationCanPushToItsOwnTenant() throws Exception {
        mvc.perform(post("/api/v1/tenants/acme/employee-events")
                        .header("Authorization", "Bearer " + token("hr-acme", "hr-acme-pass"))
                        .contentType(MediaType.APPLICATION_JSON).content(BATCH))
                .andExpect(status().isOk());
    }

    /** The case's tenant-isolation requirement: one client can never touch another's data. */
    @Test
    void integrationCannotPushToAnotherTenant() throws Exception {
        mvc.perform(post("/api/v1/tenants/globex/employee-events")
                        .header("Authorization", "Bearer " + token("hr-acme", "hr-acme-pass"))
                        .contentType(MediaType.APPLICATION_JSON).content(BATCH))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotReadAnotherTenantsEmployee() throws Exception {
        mvc.perform(get("/api/v1/tenants/globex/employees/E1001")
                        .header("Authorization", "Bearer " + token("admin-acme", "admin-acme-pass")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotPushHrEvents() throws Exception {
        mvc.perform(post("/api/v1/tenants/acme/employee-events")
                        .header("Authorization", "Bearer " + token("admin-acme", "admin-acme-pass"))
                        .contentType(MediaType.APPLICATION_JSON).content(BATCH))
                .andExpect(status().isForbidden());
    }

    @Test
    void employeeCanReadOnlyTheirOwnRecord() throws Exception {
        String token = token("E1001", "e1001-pass");

        mvc.perform(get("/api/v1/tenants/acme/employees/E1001").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/tenants/acme/employees/E1002").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void onlyOperatorsCanUseReconciliation() throws Exception {
        mvc.perform(get("/api/v1/tenants/acme/reconciliations/1")
                        .header("Authorization", "Bearer " + token("admin-acme", "admin-acme-pass")))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongPasswordGetsNoToken() throws Exception {
        mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"hr-acme\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    private String token(String username, String password) {
        return tokens.issue(username, password).orElseThrow().accessToken();
    }

    private static IncomingRecord employee(String id) {
        return new IncomingRecord(id, "CREATE", 1L, "Test User", id.toLowerCase() + "@acme.com",
                null, null, "BLR01", "DAY");
    }
}