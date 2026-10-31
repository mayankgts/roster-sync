package com.moveinsync.roster_sync.web;

import com.moveinsync.roster_sync.reconciliation.ReconciliationReport;
import com.moveinsync.roster_sync.reconciliation.ReconciliationRun;
import com.moveinsync.roster_sync.reconciliation.ReconciliationService;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/reconciliations")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReconciliationReport upload(@PathVariable String tenantId,
                                       @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new BadRequestException("file must not be empty");
        }
        try (InputStream in = file.getInputStream()) {
            return service.start(tenantId, in);
        }
    }

    @PostMapping("/{runId}/approve")
    public ReconciliationReport approve(@PathVariable String tenantId, @PathVariable long runId,
                                        @RequestBody ApproveRequest request) throws IOException {
        String approvedBy = request.approvedBy();
        if (approvedBy == null || approvedBy.isBlank() || approvedBy.length() > 100) {
            throw new BadRequestException("approvedBy is required (max 100 characters)");
        }
        return service.approve(tenantId, runId, approvedBy.strip());
    }

    @GetMapping("/{runId}")
    public ReconciliationRun get(@PathVariable String tenantId, @PathVariable long runId) {
        return service.find(tenantId, runId);
    }
}