package com.project.catalog_service.repository.jpa.data;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.project.catalog_service.model.ProductQA;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import java.util.Optional;

@Repository
public interface ProductQARepository extends JpaRepository<ProductQA, Long> {

    // --- 비관적 잠금 적용 ---
    // Q&A를 수정하는 동안 다른 트랜잭션이 해당 데이터를 수정하지 못하게 락을 겁니다.
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ProductQA> findById(Long id);
    
    // --- 낙관적 잠금 적용 ---
    //@Lock(LockModeType.OPTIMISTIC)
    Optional<ProductQA> findByQuestion(String question);
}