package com.project.userservice.repository.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import com.project.userservice.constants.AppRole;
import com.project.userservice.model.Role;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {
    
    // --- 비관적 잠금 적용 ---
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    // [JA] --- 悲観ロックの適用 ---
    Optional<Role> findByRoleName(AppRole appRole);

    // --- 낙관적 잠금 적용 ---
    //@Lock(LockModeType.OPTIMISTIC)
    // [JA] --- 楽観ロックの適用 ---
    @Override
    Optional<Role> findById(Long id);
}