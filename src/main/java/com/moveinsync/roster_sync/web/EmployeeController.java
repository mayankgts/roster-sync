package com.moveinsync.roster_sync.web;

import com.moveinsync.roster_sync.domain.Employee;
import com.moveinsync.roster_sync.domain.TransportFields;
import com.moveinsync.roster_sync.service.TransportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/employees/{employeeId}")
public class EmployeeController {

    private static final int MAX_LENGTH = 500;

    private final TransportService transportService;

    public EmployeeController(TransportService transportService) {
        this.transportService = transportService;
    }

    @GetMapping
    public Employee get(@PathVariable String tenantId, @PathVariable String employeeId) {
        return transportService.find(tenantId, employeeId);
    }

    @PutMapping("/transport")
    public Employee updateTransport(@PathVariable String tenantId, @PathVariable String employeeId,
                                    @RequestBody TransportRequest request) {
        TransportFields fields = new TransportFields(
                clean("pickupPoint", request.pickupPoint()),
                clean("specialAssistance", request.specialAssistance()));
        return transportService.update(tenantId, employeeId, fields);
    }

    private static String clean(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip();
        if (value.length() > MAX_LENGTH) {
            throw new BadRequestException(field + " must be at most " + MAX_LENGTH + " characters");
        }
        return value;
    }
}