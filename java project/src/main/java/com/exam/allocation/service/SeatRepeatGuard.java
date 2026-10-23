package com.exam.allocation.service;

import com.exam.allocation.model.Room;
import com.exam.allocation.model.Seat;
import com.exam.allocation.model.SeatAllocation;
import com.exam.allocation.model.Student;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Seat-fairness rule: <b>no student sits in the same room (class) or at the
 * same desk position more than {@link #MAX_SAME_ROOM} / {@link #MAX_SAME_SEAT}
 * times</b> across their exam history.
 *
 * <p>The guard is built from the {@code seat_allocations} rows already stored
 * in the database. The chart of the exam being regenerated is deleted before
 * the analysis, so a chart is never counted against itself and re-running an
 * allocation is idempotent with respect to the rule.
 *
 * <p>During a run the guard also records every placement it approves, so the
 * cap holds for <em>history + the new chart together</em> - a student who sat
 * in room A twice before is never placed in room A again. Desk positions are
 * counted per room as well, so a repeat at one particular seat is tracked and
 * reported at the same granularity (with equal caps the room rule binds first).
 *
 * <p>The cap is a hard <em>preference</em>, not a wall: attendance comes
 * first. When every remaining student is already at the cap for the only
 * room left, the chart is still filled and the over-cap placements are
 * counted as violations and reported to the administrator
 * (see {@link #getViolations()}).
 */
public final class SeatRepeatGuard {

    /** Maximum times a student may be seated in one room. */
    public static final int MAX_SAME_ROOM = 2;

    /** Maximum times a student may be seated at one desk position. */
    public static final int MAX_SAME_SEAT = 2;

    /** studentId -> roomId -> times seated there. */
    private final Map<Long, Map<Long, Integer>> byRoom = new HashMap<>();

    /** studentId -> "roomId|row|column|position" -> times seated there. */
    private final Map<Long, Map<String, Integer>> bySeatPosition = new HashMap<>();

    private int historySeatings;
    private int violations;

    private SeatRepeatGuard() {}

    /** Builds the guard from the stored seat allocations (may be empty). */
    public static SeatRepeatGuard analyze(List<SeatAllocation> history) {
        SeatRepeatGuard guard = new SeatRepeatGuard();
        if (history == null) return guard;
        for (SeatAllocation allocation : history) {
            if (allocation == null || allocation.getStudent() == null
                    || allocation.getStudent().getId() == null) {
                continue;
            }
            Long studentId = allocation.getStudent().getId();
            if (allocation.getRoom() != null && allocation.getRoom().getId() != null) {
                bump(guard.byRoom, studentId, allocation.getRoom().getId());
            }
            Seat seat = allocation.getSeat();
            if (seat != null) {
                Long roomId = seat.getRoom() != null ? seat.getRoom().getId()
                        : (allocation.getRoom() != null ? allocation.getRoom().getId() : null);
                if (roomId != null) {
                    bump(guard.bySeatPosition, studentId, positionKey(roomId, seat));
                }
            }
            guard.historySeatings++;
        }
        return guard;
    }

    /** True when this student already sat in this room the maximum number of times. */
    public boolean roomAtCap(Student student, Room room) {
        return roomAtCap(student, room == null ? null : room.getId());
    }

    /** Same rule addressed by room id. */
    public boolean roomAtCap(Student student, Long roomId) {
        return student == null || student.getId() == null || roomId == null
                || count(byRoom.get(student.getId()), roomId) >= MAX_SAME_ROOM;
    }

    /** True when this student already sat at this desk position the maximum number of times. */
    public boolean seatAtCap(Student student, Seat seat) {
        if (student == null || student.getId() == null || seat == null) return false;
        Long roomId = seat.getRoom() != null ? seat.getRoom().getId() : null;
        if (roomId == null) return false;
        return count(bySeatPosition.get(student.getId()), positionKey(roomId, seat)) >= MAX_SAME_SEAT;
    }

    /**
     * Records an approved placement (history + this run). A placement that
     * pushes the student over either cap is counted as a violation.
     */
    public void record(Student student, Seat seat) {
        if (student == null || student.getId() == null || seat == null) return;
        Long studentId = student.getId();
        Long roomId = seat.getRoom() != null ? seat.getRoom().getId() : null;
        boolean overCap = false;
        if (roomId != null) {
            int before = bump(byRoom, studentId, roomId);
            overCap |= before >= MAX_SAME_ROOM;
            int beforeSeat = bump(bySeatPosition, studentId, positionKey(roomId, seat));
            overCap |= beforeSeat >= MAX_SAME_SEAT;
        }
        if (overCap) violations++;
    }

    /** Past seatings that were analysed (rows of {@code seat_allocations}). */
    public int getHistorySeatings() { return historySeatings; }

    /** Placements that could not honour the caps because no alternative seat existed. */
    public int getViolations() { return violations; }

    // ------------------------------------------------------------------ helpers

    /** Stable identity of a desk position across exams (seat rows are per exam). */
    private static String positionKey(Long roomId, Seat seat) {
        return roomId + "|" + seat.getRowNumber() + "|" + seat.getColumnNumber()
                + "|" + seat.getDeskPosition();
    }

    /** Increments the counter and returns the value BEFORE the increment. */
    private static <K> int bump(Map<Long, Map<K, Integer>> counts, Long studentId, K key) {
        int before = count(counts.computeIfAbsent(studentId, id -> new HashMap<>()), key);
        counts.get(studentId).put(key, before + 1);
        return before;
    }

    private static <K> int count(Map<K, Integer> counts, K key) {
        if (counts == null) return 0;
        return counts.getOrDefault(key, 0);
    }
}
