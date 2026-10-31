package com.moveinsync.roster_sync.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class RecordValidatorTest {

    private static final Set<String> OFFICES = Set.of("BLR01", "HYD01");
    private final RecordValidator validator = new RecordValidator();

    @Test
    void acceptsAndNormalisesValidRecord() {
        var in = new IncomingRecord("E1001", "UPDATE", 3L, "Asha Rao",
                "Asha@Example.com", "+91 98450-12345", "E0007", "BLR01", "NIGHT");

        ValidationResult result = validator.validate("acme", in, OFFICES);

        assertThat(result.isValid()).isTrue();
        assertThat(result.event().hr().email()).isEqualTo("asha@example.com");
        assertThat(result.event().hr().phone()).isEqualTo("+919845012345");
    }

    @Test
    void reportsEveryBadFieldAtOnce() {
        var in = new IncomingRecord("E1001", "UPDATE", 3L, "Asha",
                "not-an-email", "12345", null, "PUNE99", null);

        ValidationResult result = validator.validate("acme", in, OFFICES);

        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).containsExactlyInAnyOrder(
                new FieldError("email", "INVALID_FORMAT"),
                new FieldError("phone", "INVALID_FORMAT"),
                new FieldError("officeCode", "UNKNOWN_OFFICE"));
    }

    @Test
    void deactivateNeedsNoHrFields() {
        var in = new IncomingRecord("E1001", "DEACTIVATE", 9L, null, null, null, null, null, null);

        assertThat(validator.validate("acme", in, OFFICES).isValid()).isTrue();
    }

    @Test
    void rejectsUnknownTypeAndMissingVersion() {
        var in = new IncomingRecord("E1001", "PROMOTE", null, null, null, null, null, null, null);

        assertThat(validator.validate("acme", in, OFFICES).errors()).containsExactlyInAnyOrder(
                new FieldError("type", "UNKNOWN_VALUE"),
                new FieldError("version", "REQUIRED"));
    }
}