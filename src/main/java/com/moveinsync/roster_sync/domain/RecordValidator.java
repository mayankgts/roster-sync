package com.moveinsync.roster_sync.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks one raw record. Collects ALL problems (not just the first) and
 * normalises good values (trimmed strings, lower-case email, digits-only phone).
 * Pure function: no database, no Spring. Office codes are passed in.
 */
public final class RecordValidator {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern PHONE = Pattern.compile("\\+?[1-9]\\d{9,14}");
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s()-]");

    public ValidationResult validate(String tenantId, IncomingRecord in, Set<String> knownOfficeCodes) {
        List<FieldError> errors = new ArrayList<>();

        String employeeId = clean(in.employeeId());
        if (employeeId == null) {
            errors.add(new FieldError("employeeId", "REQUIRED"));
        } else if (!ID.matcher(employeeId).matches()) {
            errors.add(new FieldError("employeeId", "INVALID_FORMAT"));
        }

        EventType type = parseType(in.type(), errors);

        if (in.version() == null) {
            errors.add(new FieldError("version", "REQUIRED"));
        } else if (in.version() < 1) {
            errors.add(new FieldError("version", "INVALID_VALUE"));
        }

        // DEACTIVATE carries no HR data, so only CREATE/UPDATE need field checks.
        HrFields hr = HrFields.EMPTY;
        if (type == EventType.CREATE || type == EventType.UPDATE) {
            hr = validateHrFields(in, knownOfficeCodes, errors);
        }

        if (!errors.isEmpty()) {
            return ValidationResult.invalid(errors);
        }
        return ValidationResult.valid(new EmployeeEvent(tenantId, employeeId, type, in.version(), hr));
    }

    private static EventType parseType(String raw, List<FieldError> errors) {
        String value = clean(raw);
        if (value == null) {
            errors.add(new FieldError("type", "REQUIRED"));
            return null;
        }
        try {
            return EventType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            errors.add(new FieldError("type", "UNKNOWN_VALUE"));
            return null;
        }
    }

    private static HrFields validateHrFields(IncomingRecord in, Set<String> offices, List<FieldError> errors) {
        String name = required("name", in.name(), 200, errors);

        String email = required("email", in.email(), 320, errors);
        if (email != null) {
            email = email.toLowerCase(Locale.ROOT);
            if (!EMAIL.matcher(email).matches()) {
                errors.add(new FieldError("email", "INVALID_FORMAT"));
            }
        }

        String phone = clean(in.phone());
        if (phone != null) {
            phone = PHONE_SEPARATORS.matcher(phone).replaceAll("");
            if (!PHONE.matcher(phone).matches()) {
                errors.add(new FieldError("phone", "INVALID_FORMAT"));
            }
        }

        String managerId = clean(in.managerId());
        if (managerId != null && !ID.matcher(managerId).matches()) {
            errors.add(new FieldError("managerId", "INVALID_FORMAT"));
        }

        String officeCode = required("officeCode", in.officeCode(), 32, errors);
        if (officeCode != null && !offices.contains(officeCode)) {
            errors.add(new FieldError("officeCode", "UNKNOWN_OFFICE"));
        }

        String shiftCode = optional("shiftCode", in.shiftCode(), 32, errors);

        return new HrFields(name, email, phone, managerId, officeCode, shiftCode);
    }

    private static String required(String field, String raw, int maxLength, List<FieldError> errors) {
        String value = clean(raw);
        if (value == null) {
            errors.add(new FieldError(field, "REQUIRED"));
            return null;
        }
        return checkLength(field, value, maxLength, errors);
    }

    private static String optional(String field, String raw, int maxLength, List<FieldError> errors) {
        String value = clean(raw);
        return value == null ? null : checkLength(field, value, maxLength, errors);
    }

    private static String checkLength(String field, String value, int maxLength, List<FieldError> errors) {
        if (value.length() > maxLength) {
            errors.add(new FieldError(field, "TOO_LONG"));
        }
        return value;
    }

    /** Trim; treat blank as missing. */
    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}