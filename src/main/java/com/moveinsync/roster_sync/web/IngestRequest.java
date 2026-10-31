package com.moveinsync.roster_sync.web;

import com.moveinsync.roster_sync.domain.IncomingRecord;
import java.util.List;

public record IngestRequest(List<IncomingRecord> records) {
}