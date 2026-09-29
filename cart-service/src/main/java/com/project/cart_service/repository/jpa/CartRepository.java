package com.project.cart_service.repository.jpa;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import com.project.cart_service.model.Cart;

@Repository
public interface CartRepository extends JpaRepository<Cart, Long> {
    
    // 비관적 잠금: 사용자의 장바구니를 조회할 때 쓰기 락을 걸어 다른 트랜잭션이 수정하지 못하도록 합니다.
    // 이 메서드를 호출하면 데이터베이스에 "SELECT ... FOR UPDATE" 쿼리가 실행됩니다.
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Cart> findByUserId(Long userId);
    
    // 낙관적 잠금: @Version 필드를 사용하여 동시성 충돌을 감지하고, 별도의 DB 락은 걸지 않습니다.
    // 이 메서드로 조회된 엔티티가 이후 수정될 때 @Version 필드를 기반으로 충돌을 감지합니다.
    //@Lock(LockModeType.OPTIMISTIC)
    @Override
    Optional<Cart> findById(Long id);
}