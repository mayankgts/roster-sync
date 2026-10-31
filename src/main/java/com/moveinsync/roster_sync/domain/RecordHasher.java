package com.moveinsync.roster_sync.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Deterministic 64-bit fingerprint of an employee's HR-owned state. */
public final class RecordHasher {

    // ASCII "unit separator": cannot appear in normal text, so field boundaries are unambiguous.
    private static final char SEPARATOR = '\u001F';

    private RecordHasher() {
    }

    public static long hash(String employeeId, long version, HrFields hr) {
        String canonical = String.join(String.valueOf(SEPARATOR),
                employeeId,
                Long.toString(version),
                orEmpty(hr.name()),
                orEmpty(hr.email()),
                orEmpty(hr.phone()),
                orEmpty(hr.managerId()),
                orEmpty(hr.officeCode()),
                orEmpty(hr.shiftCode()));
        byte[] digest = sha256().digest(canonical.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.wrap(digest).getLong();
    }

    /** Which bucket an employee belongs to. String.hashCode() is fixed by the Java spec, so this is stable. */
    public static int bucket(String employeeId, int bucketCount) {
        return Math.floorMod(employeeId.hashCode(), bucketCount);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}   