package com.exam.allocation.service;

import com.exam.allocation.model.Exam;
import com.exam.allocation.model.Seat;
import com.exam.allocation.repository.ExamRepository;
import com.exam.allocation.repository.SeatRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Scheduling conflict detection.
 *
 * <p>Two exams clash when they happen on the <b>same date and time</b> and
 * target an <b>overlapping audience</b> (branch and batch/course filters that
 * are blank/"all" count as wildcards).
 *
 * <p>Conflicts are surfaced in three places:
 * <ul>
 *   <li>GET /api/exams/conflicts - all clashes in the current exam schedule</li>
 *   <li>after an exam-data import - the import result lists new clashes</li>
 *   <li>after generating a seating chart - warnings about students or rooms
 *       that are booked twice in the same time slot</li>
 * </ul>
 */
@Service
public class ConflictService {

    @Autowired
    private ExamRepository examRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private ExamSemesterService examSemesterService;

    /** A detected scheduling clash, serialised to the UI as JSON. */
    public static class Conflict {
        private String type;        // SAME_SLOT | DOUBLE_BOOKED_STUDENT | DOUBLE_BOOKED_ROOM
        private Long examId;
        private Long otherExamId;
        private String message;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Long getExamId() { return examId; }
        public void setExamId(Long examId) { this.examId = examId; }
        public Long getOtherExamId() { return otherExamId; }
        public void setOtherExamId(Long otherExamId) { this.otherExamId = otherExamId; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    /** All clashes between the exams currently stored in the database. */
    @Transactional(readOnly = true)
    public List<Conflict> findConflicts() {
        List<Exam> exams = examRepository.findAll();
        List<Conflict> conflicts = new ArrayList<>();
        for (int i = 0; i < exams.size(); i++) {
            for (int j = i + 1; j < exams.size(); j++) {
                Exam a = exams.get(i);
                Exam b = exams.get(j);
                if (isSameSlot(a, b)) {
                    conflicts.add(sameSlotConflict(a, b));
                }
            }
        }
        return conflicts;
    }

    /** Conflicts involving the given exam ids only (used by the exam importer). */
    @Transactional(readOnly = true)
    public List<Conflict> findConflictsFor(Set<Long> examIds) {
        List<Conflict> all = findConflicts();
        List<Conflict> out = new ArrayList<>();
        for (Conflict conflict : all) {
            if (examIds.contains(conflict.getExamId()) || examIds.contains(conflict.getOtherExamId())) {
                out.add(conflict);
            }
        }
        return out;
    }

    /** True when both exams run at the same date/time for an overlapping audience. */
    public boolean isSameSlot(Exam a, Exam b) {
        if (a == null || b == null) return false;
        if (a.getId() != null && a.getId().equals(b.getId())) return false;
        if (a.getExamDate() == null || b.getExamDate() == null) return false;
        if (!a.getExamDate().equals(b.getExamDate())) return false;
        if (a.getExamTime() == null || b.getExamTime() == null) return false;
        if (!a.getExamTime().equals(b.getExamTime())) return false;
        return audiencesOverlap(a, b);
    }

    /** Branch, batch and semester filters are wildcards when blank/"all"/unset. */
    public boolean audiencesOverlap(Exam a, Exam b) {
        return wildcardOrEqual(a.getBranch(), b.getBranch())
                && wildcardOrEqual(a.getCourse(), b.getCourse())
                && semestersOverlap(a, b);
    }

    /**
     * Two exams only clash for students when their semester audiences intersect;
     * an exam without any semester assignment applies to every semester.
     */
    public boolean semestersOverlap(Exam a, Exam b) {
        Set<Integer> first = examSemesterService.assignedSemesters(a);
        Set<Integer> second = examSemesterService.assignedSemesters(b);
        if (first.isEmpty() || second.isEmpty()) return true;
        for (Integer semester : first) {
            if (second.contains(semester)) return true;
        }
        return false;
    }

    /**
     * Warnings for a freshly generated seating chart: students or rooms that
     * are also booked in another exam of the same time slot.
     */
    @Transactional(readOnly = true)
    public List<String> allocationWarnings(Exam exam, List<Seat> seats) {
        List<String> warnings = new ArrayList<>();
        if (exam == null || seats == null || seats.isEmpty()) return warnings;

        Set<Long> studentIds = new HashSet<>();
        for (Seat seat : seats) {
            if (seat.getAllocatedStudent() != null) studentIds.add(seat.getAllocatedStudent().getId());
        }
        Set<Long> roomIds = new HashSet<>();
        for (Seat seat : seats) {
            if (seat.getRoom() != null) roomIds.add(seat.getRoom().getId());
        }
        if (studentIds.isEmpty() && roomIds.isEmpty()) return warnings;

        for (Exam other : examRepository.findAll()) {
            if (!isSameSlot(exam, other)) continue;
            List<Seat> otherSeats = seatRepository.findByExamId(other.getId());
            if (otherSeats.isEmpty()) continue;

            List<String> doubleBooked = new ArrayList<>();
            Set<Long> otherRoomIds = new HashSet<>();
            for (Seat seat : otherSeats) {
                if (seat.getAllocatedStudent() != null && studentIds.contains(seat.getAllocatedStudent().getId())) {
                    String roll = seat.getAllocatedStudent().getRollNo();
                    doubleBooked.add(roll == null ? "#" + seat.getAllocatedStudent().getId() : roll);
                }
                if (seat.getRoom() != null) otherRoomIds.add(seat.getRoom().getId());
            }
            if (!doubleBooked.isEmpty()) {
                warnings.add(doubleBooked.size() + " student(s) are seated in both \"" + label(exam)
                        + "\" and \"" + label(other) + "\" on " + exam.getExamDate() + " at "
                        + exam.getExamTime() + " (roll no: " + abbreviate(doubleBooked) + ").");
            }

            Set<Long> sharedRooms = new HashSet<>(roomIds);
            sharedRooms.retainAll(otherRoomIds);
            if (!sharedRooms.isEmpty()) {
                warnings.add(sharedRooms.size() + " room(s) are used by both \"" + label(exam)
                        + "\" and \"" + label(other) + "\" on " + exam.getExamDate() + " at "
                        + exam.getExamTime() + ".");
            }
        }
        return warnings;
    }

    // ------------------------------------------------------------------ helpers

    private Conflict sameSlotConflict(Exam a, Exam b) {
        Conflict conflict = new Conflict();
        conflict.setType("SAME_SLOT");
        conflict.setExamId(a.getId());
        conflict.setOtherExamId(b.getId());
        conflict.setMessage("Clash: \"" + label(a) + "\" and \"" + label(b) + "\" are both scheduled for "
                + a.getExamDate() + " at " + a.getExamTime() + " for the same branch/batch audience.");
        return conflict;
    }

    private String label(Exam exam) {
        String subject = exam.getSubjectName() == null || exam.getSubjectName().isBlank()
                ? "Unnamed exam" : exam.getSubjectName().trim();
        return subject;
    }

    private boolean wildcardOrEqual(String a, String b) {
        String na = norm(a);
        String nb = norm(b);
        if (isWildcard(na) || isWildcard(nb)) return true;
        return na.equals(nb);
    }

    private boolean isWildcard(String norm) {
        return norm.isEmpty() || norm.equals("all") || norm.equals("*") || norm.equals("any");
    }

    private String abbreviate(List<String> values) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(values.size(), 6);
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append(", ");
            sb.append(values.get(i));
        }
        if (values.size() > shown) sb.append(" +").append(values.size() - shown).append(" more");
        return sb.toString();
    }

    private String norm(String v) { return v == null ? "" : v.trim().toLowerCase(); }
}
