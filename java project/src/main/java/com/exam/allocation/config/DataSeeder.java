package com.exam.allocation.config;

import com.exam.allocation.model.Department;
import com.exam.allocation.model.Student;
import com.exam.allocation.model.User;
import com.exam.allocation.repository.DepartmentRepository;
import com.exam.allocation.repository.StudentRepository;
import com.exam.allocation.repository.UserRepository;
import com.exam.allocation.service.AuditService;
import com.exam.allocation.service.UserAccountService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the initial administrator and makes sure every stored student has a
 * login account. Runs once at startup and is safe to repeat.
 *
 * <p>The administrator credentials come from configuration so they stay out of
 * the source code: {@code app.admin.username} / {@code app.admin.password}
 * (development defaults {@code admin}/{@code admin}, override with the
 * {@code APP_ADMIN_USERNAME} / {@code APP_ADMIN_PASSWORD} environment variables).
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final StudentRepository studentRepository;
    private final DepartmentRepository departmentRepository;
    private final UserAccountService userAccountService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Value("${app.admin.username:admin}")
    private String adminUsername;

    @Value("${app.admin.password:admin}")
    private String adminPassword;

    public DataSeeder(UserRepository userRepository,
                      StudentRepository studentRepository,
                      DepartmentRepository departmentRepository,
                      UserAccountService userAccountService,
                      PasswordEncoder passwordEncoder,
                      AuditService auditService) {
        this.userRepository = userRepository;
        this.studentRepository = studentRepository;
        this.departmentRepository = departmentRepository;
        this.userAccountService = userAccountService;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.findByUsername(adminUsername) == null) {
            User admin = new User(adminUsername, passwordEncoder.encode(adminPassword), User.Role.ADMIN);
            userRepository.save(admin);
            auditService.record("system", "ADMIN_SEEDED", "user:" + adminUsername,
                    "Initial administrator created from configuration.");
        }

        if (departmentRepository.count() == 0) {
            departmentRepository.save(new Department("CEC", "Computer Engineering"));
            departmentRepository.save(new Department("EEC", "Electrical & Electronics Engineering"));
            departmentRepository.save(new Department("MEC", "Mechanical Engineering"));
            departmentRepository.save(new Department("CIV", "Civil Engineering"));
        }

        int created = userAccountService.ensureStudentAccounts();
        if (created > 0) {
            auditService.record("system", "STUDENT_ACCOUNTS_SEEDED", "students",
                    created + " student login account(s) created for existing records.");
        }

        // Re-sync disabled students with their accounts (e.g. after a manual DB edit).
        for (Student student : studentRepository.findAll()) {
            if (student.getAccountStatus() == Student.AccountStatus.INACTIVE) {
                userAccountService.syncStudentStatus(student);
            }
        }
    }
}
