package com.moveinsync.roster_sync.reconciliation;

import com.moveinsync.roster_sync.domain.IncomingRecord;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Streams the nightly export one row at a time. Supports quoted fields with
 * commas and escaped quotes (""), which is enough for HR exports.
 */
public final class CsvRowReader implements AutoCloseable {

    static final List<String> HEADER = List.of(
            "employee_id", "version", "name", "email", "phone", "manager_id", "office_code", "shift_code");

    private final BufferedReader reader;
    private int lineNumber;

    public CsvRowReader(Path file) throws IOException {
        this.reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        String header = reader.readLine();
        lineNumber = 1;
        if (header != null && header.startsWith("\uFEFF")) {
            header = header.substring(1);  // Excel adds a byte-order mark
        }
        if (header == null || !parseLine(header.strip()).equals(HEADER)) {
            reader.close();
            throw new InvalidFileException("CSV header must be: " + String.join(",", HEADER));
        }
    }

    /** The next data row, or null at end of file. Blank lines are skipped. */
    public IncomingRecord next() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            List<String> f = parseLine(line);
            // A full export row is a snapshot of the employee: UPDATE means "create or update".
            return new IncomingRecord(
                    field(f, 0), "UPDATE", parseVersion(field(f, 1)),
                    field(f, 2), field(f, 3), field(f, 4), field(f, 5), field(f, 6), field(f, 7));
        }
        return null;
    }

    public int lineNumber() {
        return lineNumber;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private static String field(List<String> fields, int index) {
        return index < fields.size() ? fields.get(index) : null;
    }

    /** Missing -> null (REQUIRED); not a number -> -1 (INVALID_VALUE). */
    private static Long parseVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}