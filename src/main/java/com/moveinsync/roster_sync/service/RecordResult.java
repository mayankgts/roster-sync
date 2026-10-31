package com.moveinsync.roster_sync.service;

import com.moveinsync.roster_sync.domain.ApplyOutcome;
import com.moveinsync.roster_sync.domain.FieldError;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record RecordResult(int index, String employeeId, RecordOutcome outcome, List<FieldError> errors) {

    public static RecordResult of(int index, String employeeId, ApplyOutcome outcome) {
        return new RecordResult(index, employeeId, RecordOutcome.valueOf(outcome.name()), List.of());
    }

    public static RecordResult rejected(int index, String employeeId, List<FieldError> errors) {
        return new RecordResult(index, employeeId, RecordOutcome.REJECTED, errors);
    }

    public static RecordResult failed(int index, String employeeId) {
        return new RecordResult(index, employeeId, RecordOutcome.FAILED, List.of());
    }
    @JsonIgnore
    public boolean isProblem() {
        return outcome == RecordOutcome.REJECTED || outcome == RecordOutcome.FAILED;
    }
    
}