package com.bss.appointment.provider;

import com.bss.appointment.exception.ConflictException;
import com.bss.appointment.repository.AppointmentRepository;
import com.bss.appointment.schedule.ScheduleConfig;
import com.bss.appointment.schedule.ScheduleService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The built-in answer: the tenant's calendar and technician roster, capacity
 * derived from who is on shift, bookings counted in our own appointment table.
 */
@Component
public class RosterScheduleProvider implements ScheduleProvider {

    public static final String KEY = "roster";

    private final ScheduleService schedule;
    private final AppointmentRepository appointments;

    public RosterScheduleProvider(ScheduleService schedule, AppointmentRepository appointments) {
        this.schedule = schedule;
        this.appointments = appointments;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public List<Window> search(ScheduleConfig cfg, SlotRequest request) {
        List<Window> free = new ArrayList<>();
        for (OffsetDateTime[] w : schedule.windows()) {
            if (!withinRequested(w[0], request)) {
                continue;
            }
            int capacity = schedule.capacityAt(w[0], w[1]);
            long booked = appointments.confirmedAt(w[0], request.tenantId());
            if (booked < capacity) {
                free.add(new Window(w[0], w[1], capacity - booked));
            }
        }
        return free;
    }

    @Override
    public Booking book(ScheduleConfig cfg, BookingRequest request) {
        int capacity = schedule.capacityAt(request.start(), request.end());
        if (capacity == 0) {
            throw new ConflictException("no technician works the window starting " + request.start());
        }
        if (appointments.confirmedAt(request.start(), request.tenantId()) >= capacity) {
            throw new ConflictException("time slot " + request.start() + " is fully booked");
        }
        return new Booking(null);
    }

    @Override
    public void cancel(ScheduleConfig cfg, String externalId) {
        // our own table is the book of record; the row's status change frees the window
    }

    @Override
    public Probe probe(ScheduleConfig cfg) {
        return new Probe(true, "built-in roster");
    }

    /** TMF646 requestedTimeSlot: when the caller asks about specific windows, answer only those. */
    private static boolean withinRequested(OffsetDateTime start, SlotRequest request) {
        JsonNode asked = request.requestedTimeSlot();
        if (asked == null || asked.isEmpty()) {
            return true;
        }
        for (JsonNode slot : asked) {
            if (!slot.isObject()) {
                // the map path cast every element to Map and answered 500 for anything else;
                // typing is not the moment to improve a refusal, so the fault stays where it was
                throw new ClassCastException("requestedTimeSlot element is not an object");
            }
            JsonNode vf = slot.path("validFor");
            if (vf.isObject() && vf.hasNonNull("startDateTime") && vf.hasNonNull("endDateTime")) {
                OffsetDateTime from = OffsetDateTime.parse(vf.get("startDateTime").asText());
                OffsetDateTime to = OffsetDateTime.parse(vf.get("endDateTime").asText());
                if (!start.isBefore(from) && start.isBefore(to)) {
                    return true;
                }
            }
        }
        return false;
    }
}
