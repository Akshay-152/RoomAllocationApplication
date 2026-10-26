package com.exam.allocation.service;

import com.exam.allocation.model.Exam;
import com.exam.allocation.model.Notification;
import com.exam.allocation.model.Notification.Type;
import com.exam.allocation.model.Room;
import com.exam.allocation.model.SeatAllocation;
import com.exam.allocation.model.Student;
import com.exam.allocation.repository.NotificationRepository;
import com.exam.allocation.repository.StudentRepository;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns business events (exam published/rescheduled/cancelled, seating
 * published/withdrawn, room edited) into <b>targeted</b> student notifications.
 *
 * <p>Audience rules reuse the persisted allocation rules
 * ({@link AllocationService#matchesExam}): branch, batch/course (explicit
 * exam_eligibility rows win) and semester audience - so exactly the students
 * who can see the exam on their portal are the ones notified.
 *
 * <p>The visible title/body come from {@link NotificationTemplateService}: the
 * administrator may have edited the wording in Settings; the shipped defaults
 * reproduce the original hard-coded text. Dedupe keys are deterministic per
 * event and per resulting state (never derived from the template), so editing
 * a template or retrying a publication never spams students, while a genuinely
 * changed schedule or seat does notify again.
 */
@Service
public class ExamNotificationService {

    /** Seat-notification types, used to detect "changed vs newly published". */
    private static final Set<Type> SEAT_TYPES =
            Set.of(Type.SEAT_PUBLISHED, Type.SEAT_CHANGED, Type.SEAT_UNPUBLISHED);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final StudentRepository studentRepository;
    private final AllocationService allocationService;
    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;
    private final NotificationTemplateService notificationTemplateService;

    public ExamNotificationService(StudentRepository studentRepository,
                                   AllocationService allocationService,
                                   NotificationService notificationService,
                                   NotificationRepository notificationRepository,
                                   NotificationTemplateService notificationTemplateService) {
        this.studentRepository = studentRepository;
        this.allocationService = allocationService;
        this.notificationService = notificationService;
        this.notificationRepository = notificationRepository;
        this.notificationTemplateService = notificationTemplateService;
    }

    /** Audience of an exam: active students matching its persisted rules. */
    public List<Student> eligibleStudents(Exam exam) {
        List<Student> out = new ArrayList<>();
        for (Student student : studentRepository.findAll()) {
            if (student.getAccountStatus() != Student.AccountStatus.ACTIVE) continue;
            if (allocationService.matchesExam(student, exam)) out.add(student);
        }
        return out;
    }

    /** Exam became visible to students. */
    public NotificationService.FanOutResult examPublished(Exam exam) {
        NotificationTemplateService.Message message =
                notificationTemplateService.render(Type.EXAM_PUBLISHED, examVars(exam));
        return notificationService.notify(eligibleStudents(exam), Type.EXAM_PUBLISHED,
                message.title(), message.body(),
                "exams", exam.getId(),
                "exam:" + exam.getId() + ":published:" + stamp(exam));
    }

    /** A published exam moved to a new date/time (students heard about the old one). */
    public NotificationService.FanOutResult examRescheduled(Exam exam) {
        NotificationTemplateService.Message message =
                notificationTemplateService.render(Type.EXAM_RESCHEDULED, examVars(exam));
        return notificationService.notify(eligibleStudents(exam), Type.EXAM_RESCHEDULED,
                message.title(), message.body(),
                "exams", exam.getId(),
                "exam:" + exam.getId() + ":rescheduled:" + stamp(exam));
    }

    /** Exam was cancelled. */
    public NotificationService.FanOutResult examCancelled(Exam exam) {
        NotificationTemplateService.Message message =
                notificationTemplateService.render(Type.EXAM_CANCELLED, examVars(exam));
        return notificationService.notify(eligibleStudents(exam), Type.EXAM_CANCELLED,
                message.title(), message.body(),
                "exams", exam.getId(),
                "exam:" + exam.getId() + ":cancelled:" + stamp(exam));
    }

    /**
     * Seating was published. Each seated student is notified once; a student
     * whose seat differs from the one previously announced gets a
     * "changed" notification instead of a duplicate "published" one.
     */
    public NotificationService.FanOutResult seatingPublished(Exam exam, List<SeatAllocation> allocations) {
        NotificationService.FanOutResult combined = new NotificationService.FanOutResult();
        for (SeatAllocation allocation : allocations) {
            if (allocation.getStatus() != SeatAllocation.Status.PUBLISHED) continue;
            Student student = allocation.getStudent();
            if (student == null) continue;
            String seatRef = describeSeat(allocation);
            String baseKey = "seat:" + exam.getId() + ":";
            Notification previous = latestSeatNotification(student.getId(), exam.getId());
            String previousKey = previous == null ? null : previous.getDedupeKey();

            if (previous != null && previous.getType() != Type.SEAT_UNPUBLISHED && previousKey != null
                    && previousKey.equals(baseKey + seatRef)) {
                continue;   // identical seat already announced - retry, not news
            }
            boolean changed = previous != null && previous.getType() != Type.SEAT_UNPUBLISHED;
            Type type = changed ? Type.SEAT_CHANGED : Type.SEAT_PUBLISHED;
            NotificationTemplateService.Message message =
                    notificationTemplateService.render(type, seatVars(exam, seatRef));
            NotificationService.FanOutResult one = notificationService.notify(student, type,
                    message.title(), message.body(), "seat", exam.getId(),
                    baseKey + (changed ? "changed:" : "") + seatRef);
            combine(combined, one);
        }
        return combined;
    }

    /** A published seating chart was withdrawn and is no longer visible to students. */
    public NotificationService.FanOutResult seatingUnpublished(Exam exam, List<SeatAllocation> allocations) {
        NotificationService.FanOutResult combined = new NotificationService.FanOutResult();
        for (SeatAllocation allocation : allocations) {
            if (allocation.getStatus() != SeatAllocation.Status.PUBLISHED) continue;
            Student student = allocation.getStudent();
            if (student == null || student.getId() == null) continue;

            Notification previous = latestSeatNotification(student.getId(), exam.getId());
            String previousId = previous != null && previous.getId() != null
                    ? previous.getId().toString()
                    : String.valueOf(allocation.getId());
            String dedupeKey = "seat:" + exam.getId() + ":withdrawn:"
                    + student.getId() + ":" + previousId;
            String seatRef = describeSeat(allocation);
            NotificationTemplateService.Message message =
                    notificationTemplateService.render(Type.SEAT_UNPUBLISHED, seatVars(exam, seatRef));
            combine(combined, notificationService.notify(student, Type.SEAT_UNPUBLISHED,
                    message.title(), message.body(), "seat", exam.getId(), dedupeKey));
        }
        return combined;
    }

    /**
     * A room used by published seating was edited (number/building/floor).
     * Only students seated in that room for a published exam are notified.
     */
    public NotificationService.FanOutResult roomChanged(Room room, List<SeatAllocation> affected) {
        NotificationService.FanOutResult combined = new NotificationService.FanOutResult();
        String roomLabel = room.getDisplayName();
        for (SeatAllocation allocation : affected) {
            if (allocation.getStatus() != SeatAllocation.Status.PUBLISHED) continue;
            Exam exam = allocation.getExam();
            Student student = allocation.getStudent();
            if (exam == null || student == null
                    || exam.getStatus() != Exam.ExamStatus.PUBLISHED) continue;
            Map<String, String> vars = new LinkedHashMap<>();
            vars.put("subject", label(exam));
            vars.put("room", roomLabel);
            vars.put("when", describe(exam));
            NotificationTemplateService.Message message =
                    notificationTemplateService.render(Type.ROOM_CHANGED, vars);
            combine(combined, notificationService.notify(student, Type.ROOM_CHANGED,
                    message.title(), message.body(), "seat", exam.getId(),
                    "exam:" + exam.getId() + ":roomchanged:" + room.getId() + ":"
                            + room.getRoomNumber() + ":" + room.getBuilding() + ":" + room.getFloor()));
        }
        return combined;
    }

    // ------------------------------------------------------------------ helpers

    /** Placeholder values shared by the three exam lifecycle templates. */
    private static Map<String, String> examVars(Exam exam) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("subject", label(exam));
        vars.put("when", describe(exam));
        vars.put("date", exam.getExamDate() == null ? "" : exam.getExamDate().toString());
        vars.put("time", exam.getExamTime() == null ? "" : exam.getExamTime().format(TIME));
        return vars;
    }

    /** Placeholder values for seat published/changed templates. */
    private static Map<String, String> seatVars(Exam exam, String seatRef) {
        Map<String, String> vars = examVars(exam);
        vars.put("seat", seatRef);
        return vars;
    }

    /** The newest seat lifecycle notification for this student and exam, or null. */
    private Notification latestSeatNotification(Long studentId, Long examId) {
        return notificationRepository
                .findTopByStudentIdAndExamIdAndTypeInOrderByCreatedAtDesc(studentId, examId, SEAT_TYPES)
                .orElse(null);
    }

    private void combine(NotificationService.FanOutResult into, NotificationService.FanOutResult one) {
        into.add(one);
    }

    private static String label(Exam exam) {
        String subject = exam.getSubjectName() == null || exam.getSubjectName().isBlank()
                ? "Unnamed exam" : exam.getSubjectName().trim();
        return "\"" + subject + "\"";
    }

    private static String describe(Exam exam) {
        String date = exam.getExamDate() == null ? "a date to be announced" : exam.getExamDate().toString();
        String time = exam.getExamTime() == null ? "" : " at " + exam.getExamTime().format(TIME);
        return "on " + date + time;
    }

    /** Deterministic part of the dedupe key: schedule identity of the exam. */
    private static String stamp(Exam exam) {
        return (exam.getExamDate() == null ? "-" : exam.getExamDate().format(DAY))
                + "T" + (exam.getExamTime() == null ? "-" : exam.getExamTime().format(TIME));
    }

    /** Human-readable seat reference, e.g. "Room R-101, seat R3-C04 (row 3, column 4)". */
    private static String describeSeat(SeatAllocation allocation) {
        Room room = allocation.getRoom();
        String roomLabel = room == null ? "an unassigned room" : "Room " + room.getDisplayName();
        String seatCode = allocation.getSeat() == null ? "?"
                : "R" + allocation.getSeat().getRowNumber()
                        + "-C" + String.format("%02d", allocation.getSeat().getColumnNumber());
        return roomLabel + ", seat " + seatCode;
    }
}
