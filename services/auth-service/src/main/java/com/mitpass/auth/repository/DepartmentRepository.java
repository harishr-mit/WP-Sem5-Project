package com.mitpass.auth.repository;

import com.mitpass.auth.domain.Department;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, String> {}
