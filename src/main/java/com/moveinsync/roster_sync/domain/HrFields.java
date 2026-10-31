package com.moveinsync.roster_sync.domain;

/**
 * Fields owned by the HR system. HR events carry the full set every time
 * (a snapshot, not a delta), so the highest version is always complete.
 */
public record HrFields(
        String name,
        String email,
        String phone,
        String managerId,
        String officeCode,
        String shiftCode) {

    public static final HrFields EMPTY = new HrFields(null, null, null, null, null, null);

    // Records auto-generate toString() with every field. That would leak
    // name/email/phone into any log line that prints this object.
    @Override
    public String toString() {
        return "HrFields[officeCode=" + officeCode + ", shiftCode=" + shiftCode + ", pii=<redacted>]";
    }
}