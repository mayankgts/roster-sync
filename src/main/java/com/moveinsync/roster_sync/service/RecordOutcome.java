package com.moveinsync.roster_sync.service;

public enum RecordOutcome {
    APPLIED,
    NO_CHANGE,
    DUPLICATE,
    STALE,
    REJECTED,  // failed validation
    FAILED     // valid, but something unexpected went wrong while applying
}