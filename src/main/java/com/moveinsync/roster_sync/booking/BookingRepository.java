package com.moveinsync.roster_sync.booking;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BookingRepository {

    private final JdbcClient jdbc;

    public BookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Cancels the employee's CONFIRMED bookings on trips that have not departed yet,
     * and frees one seat per cancelled booking, in a single statement so the two
     * can never disagree. Returns how many bookings were cancelled (0 if run again).
     */
    public long cancelFutureBookings(String tenantId, String employeeId, String reason) {
        return jdbc.sql("""
                WITH cancelled AS (
                    UPDATE booking b
                       SET status = 'CANCELLED', cancel_reason = :reason, cancelled_at = now()
                      FROM trip t
                     WHERE t.id = b.trip_id
                       AND b.tenant_id = :tenantId
                       AND b.employee_id = :employeeId
                       AND b.status = 'CONFIRMED'
                       AND t.departs_at > now()
                    RETURNING b.trip_id
                ),
                freed AS (
                    UPDATE trip SET seats_booked = seats_booked - 1
                      FROM cancelled
                     WHERE trip.id = cancelled.trip_id
                    RETURNING trip.id
                )
                SELECT count(*) FROM cancelled
                """)
                .param("reason", reason)
                .param("tenantId", tenantId)
                .param("employeeId", employeeId)
                .query(Long.class)
                .single();
    }
}