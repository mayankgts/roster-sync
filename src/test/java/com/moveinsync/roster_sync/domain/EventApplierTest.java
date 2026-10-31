package com.moveinsync.roster_sync.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class EventApplierTest {

    private final EventApplier applier = new EventApplier();

    @Test
    void firstEventCreatesActiveEmployee() {
        ApplyResult result = applier.apply(null, update(1, "BLR01", "DAY"));

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.APPLIED);
        assertThat(result.employee().isActive()).isTrue();
        assertThat(result.employee().hrVersion()).isEqualTo(1);
    }

    /** The exact example from the case study. */
    @Test
    void transferV7BeforeShiftChangeV6_v6IsIgnoredAsStale() {
        Employee afterV7 = applier.apply(null, update(7, "HYD01", "NIGHT")).employee();

        ApplyResult v6 = applier.apply(afterV7, update(6, "BLR01", "DAY"));

        assertThat(v6.outcome()).isEqualTo(ApplyOutcome.STALE);
        assertThat(v6.employee().hr().officeCode()).isEqualTo("HYD01");
        assertThat(v6.employee().hr().shiftCode()).isEqualTo("NIGHT");
    }

    @Test
    void sameVersionTwiceIsDuplicate() {
        Employee current = applier.apply(null, update(3, "BLR01", "DAY")).employee();

        ApplyResult again = applier.apply(current, update(3, "BLR01", "DAY"));

        assertThat(again.outcome()).isEqualTo(ApplyOutcome.DUPLICATE);
        assertThat(again.employee()).isEqualTo(current);
    }

    @Test
    void hrUpdateNeverTouchesTransportOwnedFields() {
        Employee current = new Employee("acme", "E1", EmployeeStatus.ACTIVE, 7,
                hr("HYD01", "NIGHT"), new TransportFields("Gate 3", "wheelchair"), 2);

        ApplyResult result = applier.apply(current, update(8, "BLR01", "NIGHT"));

        assertThat(result.employee().transport()).isEqualTo(new TransportFields("Gate 3", "wheelchair"));
        assertThat(result.employee().adminVersion()).isEqualTo(2);
        assertThat(result.changedFields()).containsExactly("officeCode");
    }

    @Test
    void deactivateBeforeCreateLeavesTombstoneThatBlocksLateCreate() {
        Employee tombstone = applier.apply(null, deactivate(5)).employee();
        assertThat(tombstone.isActive()).isFalse();

        var lateCreate = new EmployeeEvent("acme", "E1", EventType.CREATE, 3, hr("BLR01", "DAY"));

        assertThat(applier.apply(tombstone, lateCreate).outcome()).isEqualTo(ApplyOutcome.STALE);
    }

    @Test
    void newerVersionWithIdenticalDataIsNoChange() {
        Employee current = applier.apply(null, update(7, "HYD01", "NIGHT")).employee();

        ApplyResult result = applier.apply(current, update(9, "HYD01", "NIGHT"));

        assertThat(result.outcome()).isEqualTo(ApplyOutcome.NO_CHANGE);
        assertThat(result.employee().hrVersion()).isEqualTo(9);
    }

    @Test
    void replayingTheSameFeedTwiceChangesNothing() {
        List<EmployeeEvent> feed = sampleFeed();
        Employee once = applyAll(null, feed);

        Employee twice = applyAll(once, feed);

        assertThat(twice).isEqualTo(once);
    }

    @Test
    void deliveryOrderDoesNotAffectFinalState() {
        List<EmployeeEvent> feed = sampleFeed();
        Employee expected = applyAll(null, feed);

        List<EmployeeEvent> shuffled = new ArrayList<>(feed);
        for (int seed = 0; seed < 50; seed++) {
            Collections.shuffle(shuffled, new Random(seed));
            assertThat(applyAll(null, shuffled)).isEqualTo(expected);
        }
    }

    // ---- helpers ----

    private Employee applyAll(Employee start, List<EmployeeEvent> events) {
        Employee state = start;
        for (EmployeeEvent event : events) {
            ApplyResult result = applier.apply(state, event);
            if (result.needsSave()) {
                state = result.employee();
            }
        }
        return state;
    }

    private static List<EmployeeEvent> sampleFeed() {
        return List.of(
                update(1, "BLR01", "DAY"),
                update(2, "HYD01", "DAY"),
                deactivate(3),
                update(4, "HYD01", "NIGHT"));
    }

    private static EmployeeEvent update(long version, String office, String shift) {
        return new EmployeeEvent("acme", "E1", EventType.UPDATE, version, hr(office, shift));
    }

    private static EmployeeEvent deactivate(long version) {
        return new EmployeeEvent("acme", "E1", EventType.DEACTIVATE, version, null);
    }

    private static HrFields hr(String office, String shift) {
        return new HrFields("Asha Rao", "asha@example.com", null, null, office, shift);
    }
}