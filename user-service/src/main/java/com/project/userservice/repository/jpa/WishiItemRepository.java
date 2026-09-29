package com.project.userservice.repository.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import com.project.userservice.model.WishItem;

import java.util.Optional;
import java.util.List;

@Repository
public interface WishiItemRepository extends JpaRepository<WishItem, Long> {

    // --- 비관적 잠금 적용 ---
    // 위시리스트를 수정하거나 삭제할 때 다른 트랜잭션의 접근을 막기 위해 락을 겁니다.
    // 이 메서드를 호출하면 데이터베이스에 SELECT ... FOR UPDATE 쿼리가 실행됩니다.
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    @Override
    Optional<WishItem> findById(Long id);

    /** 회원 탈퇴 최종 처리용. 위시리스트는 취향 기록이라 탈퇴하면 남길 이유가 없다. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from WishItem w where w.user.userId = :userId")
    int deleteByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
}