package com.moveinsync.roster_sync.domain;

/** Fields owned by the transport admin. HR events never write these. */
public record TransportFields(String pickupPoint, String specialAssistance) {

    public static final TransportFields EMPTY = new TransportFields(null, null);

    @Override
    public String toString() {
        return "TransportFields[<redacted>]";
    }
}