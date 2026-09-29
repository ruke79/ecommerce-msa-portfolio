package com.project.catalog_service.repository.jpa.data;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import com.project.catalog_service.model.ProductDetails;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

@Repository
public interface ProductDetailsRepository extends JpaRepository<ProductDetails, Long> {
    
    // --- 비관적 잠금 적용 ---
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ProductDetails> findDistinctAllByProductProductIdIn(List<Long> productIds);
    
    // --- 낙관적 잠금 적용 ---
    //@Lock(LockModeType.OPTIMISTIC)
    @Query("select distinct d from ProductDetails d")
    List<ProductDetails> findDistinctAll();
}