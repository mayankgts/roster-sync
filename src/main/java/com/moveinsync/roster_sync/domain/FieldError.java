package com.moveinsync.roster_sync.domain;

/** A validation failure: which field, and a machine-readable code. Never the value. */
public record FieldError(String field, String code) {
}