package com.exam.allocation.repository;

import com.exam.allocation.model.Batch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BatchRepository extends JpaRepository<Batch, Long> {
    Batch findByNameIgnoreCase(String name);
    List<Batch> findByOrderByNameAsc();
}
