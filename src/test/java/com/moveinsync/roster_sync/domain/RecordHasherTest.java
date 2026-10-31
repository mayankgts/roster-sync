package com.moveinsync.roster_sync.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecordHasherTest {

    private static final HrFields HR = new HrFields("Asha", "a@x.com", null, null, "BLR01", "DAY");

    @Test
    void sameInputSameHash() {
        assertThat(RecordHasher.hash("E1", 1, HR)).isEqualTo(RecordHasher.hash("E1", 1, HR));
    }

    @Test
    void fieldBoundariesMatter() {
        long ab = RecordHasher.hash("E1", 1, new HrFields("ab", "c", null, null, null, null));
        long a = RecordHasher.hash("E1", 1, new HrFields("a", "bc", null, null, null, null));
        assertThat(ab).isNotEqualTo(a);
    }

    @Test
    void versionIsPartOfTheHash() {
        assertThat(RecordHasher.hash("E1", 1, HR)).isNotEqualTo(RecordHasher.hash("E1", 2, HR));
    }
}