package com.moveinsync.roster_sync.domain;

import java.util.List;

/** Either a valid event, or the list of reasons it was rejected. Never both. */
public record ValidationResult(EmployeeEvent event, List<FieldError> errors) {

    public static ValidationResult valid(EmployeeEvent event) {
        return new ValidationResult(event, List.of());
    }

    public static ValidationResult invalid(List<FieldError> errors) {
        return new ValidationResult(null, List.copyOf(errors));
    }

    public boolean isValid() {
        return event != null;
    }
}