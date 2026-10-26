package com.exam.allocation.repository;

import com.exam.allocation.model.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StudentRepository extends JpaRepository<Student, Long> {
    Student findByRollNo(String rollNo);
    List<Student> findByBatch(String batch);
    long countByBatch(String batch);

    /** Subscriber search by roll number or name (admin subscribers screen). */
    List<Student> findByRollNoContainingIgnoreCaseOrNameContainingIgnoreCase(String rollNo, String name);
}
