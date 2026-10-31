package com.moveinsync.roster_sync.domain;

/**
 * A record exactly as HR sent it, before any checks. Loose types on purpose:
 * type is a String (not the enum) and version is a Long (nullable), so bad
 * input becomes a reported error instead of a crash while parsing.
 */
public record IncomingRecord(
        String employeeId,
        String type,
        Long version,
        String name,
        String email,
        String phone,
        String managerId,
        String officeCode,
        String shiftCode) {

    @Override
    public String toString() {
        return "IncomingRecord[employeeId=" + employeeId + ", type=" + type + ", version=" + version + "]";
    }
}