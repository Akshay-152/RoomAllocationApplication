package com.exam.allocation.service;

import com.exam.allocation.model.Student;
import com.exam.allocation.model.User;
import com.exam.allocation.repository.StudentRepository;
import com.exam.allocation.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Login-account management: administrators are seeded from configuration and a
 * student account is created automatically when the administrator adds a student
 * (initial username = initial password = the roll number).
 */
@Service
public class UserAccountService implements UserDetailsService {

    @Autowired private UserRepository userRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username);
        if (user == null) {
            throw new UsernameNotFoundException(username);
        }
        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                user.isEnabled(), true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
    }

    /** Creates (or re-enables) the login account of a student using the roll number as the initial password. */
    @Transactional
    public User createStudentAccount(Student student) {
        String rollNo = student.getRollNo() == null ? "" : student.getRollNo().trim();
        if (rollNo.isBlank()) {
            throw new IllegalArgumentException("A roll number is required to create a student login account.");
        }
        User user = userRepository.findByUsername(rollNo);
        boolean fresh = user == null;
        if (fresh) {
            user = new User(rollNo, passwordEncoder.encode(rollNo), User.Role.STUDENT);
            user.setStudent(student);
            user.setMustChangePassword(true);
        }
        user.setEnabled(student.getAccountStatus() == Student.AccountStatus.ACTIVE);
        return userRepository.save(user);
    }

    /** Mirrors the student's account status onto the login account (used on add/update/reset). */
    @Transactional
    public void syncStudentStatus(Student student) {
        User user = userRepository.findByUsername(student.getRollNo());
        if (user != null) {
            user.setEnabled(student.getAccountStatus() == Student.AccountStatus.ACTIVE);
            userRepository.save(user);
        }
    }

    /** Administrator password reset: a new initial password, flagged for change at next login. */
    @Transactional
    public void resetStudentPassword(String rollNo, String newPassword) {
        User user = userRepository.findByUsername(rollNo);
        if (user == null) {
            throw new IllegalArgumentException("No login account exists for roll number \"" + rollNo + "\".");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        userRepository.save(user);
    }

    /** Self-service password change after verifying the current password. */
    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        User user = userRepository.findByUsername(username);
        if (user == null) {
            throw new IllegalArgumentException("Account not found.");
        }
        if (currentPassword == null || currentPassword.isBlank() || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new IllegalArgumentException("Your current password is incorrect.");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("The new password must be at least 6 characters long.");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
    }

    /** Removes the login account of a student (called when the student record is deleted). */
    @Transactional
    public void deleteAccountFor(String rollNo) {
        User user = userRepository.findByUsername(rollNo);
        if (user != null) {
            userRepository.delete(user);
        }
    }

    /** Ensures every stored student has a working login account (safe to run repeatedly). */
    @Transactional
    public int ensureStudentAccounts() {
        int created = 0;
        for (Student student : studentRepository.findAll()) {
            if (student.getRollNo() == null || student.getRollNo().isBlank()) continue;
            if (userRepository.findByUsername(student.getRollNo()) == null) {
                createStudentAccount(student);
                created++;
            }
        }
        return created;
    }
}
