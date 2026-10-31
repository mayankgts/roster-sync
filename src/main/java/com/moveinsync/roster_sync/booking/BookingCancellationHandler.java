package com.moveinsync.roster_sync.booking;

import com.moveinsync.roster_sync.domain.Employee;
import com.moveinsync.roster_sync.outbox.OutboxEvent;
import com.moveinsync.roster_sync.outbox.OutboxEventHandler;
import com.moveinsync.roster_sync.persistence.EmployeeRepository;
import com.moveinsync.roster_sync.service.ChangeType;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reacts to EMPLOYEE_DEACTIVATED: cancels the leaver's future bookings and frees their seats.
 * Runs inside the relay's transaction, so the cancellation and "event published" commit together.
 */
@Component
public class BookingCancellationHandler implements OutboxEventHandler {

    static final String REASON = "EMPLOYEE_DEACTIVATED";
    private static final Logger log = LoggerFactory.getLogger(BookingCancellationHandler.class);

    private final EmployeeRepository employees;
    private final BookingRepository bookings;
    private final MeterRegistry registry;

    public BookingCancellationHandler(EmployeeRepository employees, BookingRepository bookings,
                                      MeterRegistry registry) {
        this.employees = employees;
        this.bookings = bookings;
        this.registry = registry;
    }

    @Override
    public void handle(OutboxEvent event) {
        if (!ChangeType.EMPLOYEE_DEACTIVATED.name().equals(event.eventType())) {
            return;
        }

        // Same per-employee lock as HR and admin writes: a rehire can't slip in mid-cancel.
        employees.lock(event.tenantId(), event.employeeId());

        // Act on the CURRENT state, not on what the event said. If the employee was
        // reactivated before this event was processed, cancelling would be wrong.
        Optional<Employee> current = employees.find(event.tenantId(), event.employeeId());
        if (current.isEmpty() || current.get().isActive()) {
            log.info("Skip booking cancellation eventId={} employeeId={} reason=employee-active",
                    event.id(), event.employeeId());
            return;
        }

        long cancelled = bookings.cancelFutureBookings(event.tenantId(), event.employeeId(), REASON);
        registry.counter("roster.bookings.cancelled", "tenant", event.tenantId()).increment(cancelled);
        log.info("Cancelled bookings eventId={} tenant={} employeeId={} count={}",
                event.id(), event.tenantId(), event.employeeId(), cancelled);
    }
}