package com.exam.allocation.repository;

import com.exam.allocation.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    /** Fetch join so callers outside a JPA session can read the student without a LazyInitializationException. */
    @Query("select u from User u left join fetch u.student where u.username = :username")
    User findByUsername(@Param("username") String username);
    User findByStudentId(Long studentId);
}
