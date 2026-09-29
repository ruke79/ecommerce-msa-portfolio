package com.project.cart_service.repository.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import com.project.cart_service.model.CartProduct;
import java.util.Optional;

@Repository
public interface CartProductRepository extends JpaRepository<CartProduct, Long> {
        
    // 비관적 잠금: 상품 ID로 장바구니 상품을 찾을 때 락을 걸어 안전하게 수량 변경 등을 처리합니다.
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CartProduct> findByProductId(long productId);

    // 낙관적 잠금: @Version 필드를 사용하여 동시성 충돌을 감지합니다.
    // 이 메서드는 _uid로 엔티티를 찾고, 이후 수정 시 버전 체크를 하도록 합니다.
    //@Lock(LockModeType.OPTIMISTIC)
    Optional<CartProduct> findBy_uid(String uid);
}