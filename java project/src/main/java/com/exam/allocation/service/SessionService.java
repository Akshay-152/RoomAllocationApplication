package com.exam.allocation.service;

import com.exam.allocation.model.Student;
import com.exam.allocation.model.User;
import com.exam.allocation.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the signed-in account inside an open JPA session and flattens it into a
 * plain map. The login filter and the /api/auth/me endpoint both run outside a
 * transaction, so reading the lazy student association there would throw a
 * LazyInitializationException; this service keeps the session open instead.
 */
@Service
public class SessionService {

    private final UserRepository userRepository;

    public SessionService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** @return username/role/mustChangePassword (+ student details) or an empty map when unknown. */
    @Transactional(readOnly = true)
    public Map<String, Object> describe(String username) {
        Map<String, Object> out = new LinkedHashMap<>();
        User user = userRepository.findByUsername(username);
        if (user == null) {
            return out;
        }
        out.put("username", user.getUsername());
        out.put("role", user.getRole().name());
        out.put("mustChangePassword", user.isMustChangePassword());
        Student student = user.getStudent();
        if (student != null) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", student.getId());
            info.put("rollNo", student.getRollNo());
            info.put("name", student.getName());
            info.put("branch", student.getBranch());
            info.put("batch", student.getBatch());
            out.put("student", info);
        }
        return out;
    }

    /**
     * Loads the student linked to an account inside a transaction so the entity is
     * fully initialized before it is returned. Controllers must not read the lazy
     * user.student association outside a session, so they call this instead.
     *
     * @throws IllegalArgumentException when the account has no student record.
     */
    @Transactional(readOnly = true)
    public Student requireStudent(String username) {
        User user = userRepository.findByUsername(username);
        if (user == null || user.getStudent() == null) {
            throw new IllegalArgumentException("This account is not linked to a student record.");
        }
        return user.getStudent();
    }
}
