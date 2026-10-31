package com.moveinsync.roster_sync.reconciliation;

public class InvalidFileException extends RuntimeException {
    public InvalidFileException(String message) {
        super(message);
    }
}