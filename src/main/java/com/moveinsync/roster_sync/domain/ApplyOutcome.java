package com.moveinsync.roster_sync.domain;

public enum ApplyOutcome {
    APPLIED,    // newer version, data changed  -> save + write outbox event
    NO_CHANGE,  // newer version, identical data -> save version only, no event
    DUPLICATE,  // same version seen before      -> do nothing
    STALE       // older version                 -> do nothing
}