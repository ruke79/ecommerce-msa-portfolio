package com.project.cart_service.repository.jpa;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * 사용자의 장바구니가 없을 때만 만든다 — 이미 있으면(다른 요청이 방금 만들었어도) 아무것도 하지 않는다.
     *
     * <p>"읽고 없으면 save" 는 처음 담기가 동시에 오면 둘 다 만든다. 유니크 인덱스(V5)만 두면 늦은 쪽이 중복 키로 실패하고,
     * PostgreSQL 은 그 트랜잭션을 통째로 무효로 만들어 이어서 읽을 수도 없다. {@code ON CONFLICT DO NOTHING} 은 앞선 쪽의
     * 커밋을 기다렸다가 조용히 넘어가므로, 뒤이은 {@code findByUserId} 가 그 장바구니를 읽는다(READ COMMITTED).
     * [JA] 無ければ作る。同時の初回追加でも片方は何もせず、続く findByUserId が先に作られたカートを読む。
     *
     * @return 만들었으면 1, 이미 있었으면 0
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = "INSERT INTO cart.cart (cart_id, user_id, version, created_at, updated_at) "
            + "VALUES (:cartId, :userId, 0, LOCALTIMESTAMP, LOCALTIMESTAMP) ON CONFLICT DO NOTHING")
    int insertIfAbsent(@Param("cartId") Long cartId, @Param("userId") Long userId);
}