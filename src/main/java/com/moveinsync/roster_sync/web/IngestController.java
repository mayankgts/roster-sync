package com.moveinsync.roster_sync.web;

import com.moveinsync.roster_sync.service.BatchResult;
import com.moveinsync.roster_sync.service.IngestService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/employee-events")
public class IngestController {

    static final int MAX_BATCH_SIZE = 10_000;

    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping
    public BatchResult ingest(@PathVariable String tenantId, @RequestBody IngestRequest request) {
        if (request.records() == null || request.records().isEmpty()) {
            throw new BadRequestException("records must not be empty");
        }
        if (request.records().size() > MAX_BATCH_SIZE) {
            throw new BadRequestException("records must not exceed " + MAX_BATCH_SIZE + " per request");
        }
        return ingestService.ingest(tenantId, request.records());
    }
}