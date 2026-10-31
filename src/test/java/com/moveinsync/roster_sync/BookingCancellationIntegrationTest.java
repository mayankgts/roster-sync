package com.moveinsync.roster_sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.moveinsync.roster_sync.domain.IncomingRecord;
import com.moveinsync.roster_sync.outbox.OutboxRelay;
import com.moveinsync.roster_sync.service.IngestService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = "roster.outbox.relay.enabled=false")
@Import(TestcontainersConfiguration.class)
class BookingCancellationIntegrationTest {

    @Autowired IngestService ingestService;
    @Autowired OutboxRelay relay;
    @Autowired JdbcClient jdbc;

    private long tomorrow;
    private long nextWeek;
    private long yesterday;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE booking, trip, employee, outbox RESTART IDENTITY CASCADE").update();
        ingest(hrRecord("E1", "CREATE", 1));
        ingest(hrRecord("E2", "CREATE", 1));
        relay.publishPending();  // flush the CREATE events so tests only see what they cause

        tomorrow = trip("1 day");
        nextWeek = trip("7 days");
        yesterday = trip("-1 day");
        book("E1", tomorrow);
        book("E2", tomorrow);
        book("E1", nextWeek);
        book("E1", yesterday);
    }

    @Test
    void leaverLosesFutureBookingsAndSeatsAreFreed() {
        ingest(deactivate("E1", 2));

        relay.publishPending();

        assertThat(bookingStatus("E1", tomorrow)).isEqualTo("CANCELLED");
        assertThat(bookingStatus("E1", nextWeek)).isEqualTo("CANCELLED");
        assertThat(bookingStatus("E1", yesterday)).isEqualTo("CONFIRMED");  // past trips untouched
        assertThat(bookingStatus("E2", tomorrow)).isEqualTo("CONFIRMED");   // colleague untouched
        assertThat(seatsBooked(tomorrow)).isEqualTo(1);
        assertThat(seatsBooked(nextWeek)).isZero();
        assertThat(seatsBooked(yesterday)).isEqualTo(1);
    }

    @Test
    void redeliveredEventDoesNotFreeSeatsTwice() {
        ingest(deactivate("E1", 2));
        relay.publishPending();

        // Simulate at-least-once delivery: the relay crashed before recording the publish.
        jdbc.sql("UPDATE outbox SET published_at = NULL").update();
        relay.publishPending();

        assertThat(seatsBooked(tomorrow)).isEqualTo(1);
        assertThat(seatsBooked(nextWeek)).isZero();
    }

    @Test
    void employeeRehiredBeforeEventIsProcessedKeepsBookings() {
        ingest(deactivate("E1", 2));
        ingest(hrRecord("E1", "UPDATE", 3));  // rehire lands before the relay runs

        relay.publishPending();

        assertThat(bookingStatus("E1", tomorrow)).isEqualTo("CONFIRMED");
        assertThat(seatsBooked(tomorrow)).isEqualTo(2);
    }

    // ---- helpers ----

    private void ingest(IncomingRecord record) {
        ingestService.ingest("acme", List.of(record));
    }

    private static IncomingRecord hrRecord(String id, String type, long version) {
        return new IncomingRecord(id, type, version, "Test User", id.toLowerCase() + "@acme.com",
                null, null, "BLR01", "DAY");
    }

    private static IncomingRecord deactivate(String id, long version) {
        return new IncomingRecord(id, "DEACTIVATE", version, null, null, null, null, null, null);
    }

    private long trip(String offset) {
        return jdbc.sql("""
                INSERT INTO trip (tenant_id, office_code, departs_at, capacity, seats_booked)
                VALUES ('acme', 'BLR01', now() + CAST(:offset AS interval), 4, 0)
                RETURNING id
                """)
                .param("offset", offset)
                .query(Long.class)
                .single();
    }

    private void book(String employeeId, long tripId) {
        jdbc.sql("INSERT INTO booking (tenant_id, employee_id, trip_id, status) VALUES ('acme', :e, :t, 'CONFIRMED')")
                .param("e", employeeId).param("t", tripId).update();
        jdbc.sql("UPDATE trip SET seats_booked = seats_booked + 1 WHERE id = :t")
                .param("t", tripId).update();
    }

    private String bookingStatus(String employeeId, long tripId) {
        return jdbc.sql("SELECT status FROM booking WHERE employee_id = :e AND trip_id = :t")
                .param("e", employeeId).param("t", tripId)
                .query(String.class).single();
    }

    private int seatsBooked(long tripId) {
        return jdbc.sql("SELECT seats_booked FROM trip WHERE id = :t")
                .param("t", tripId).query(Integer.class).single();
    }
}