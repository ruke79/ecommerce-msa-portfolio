package com.project.userservice.repository.jpa;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import com.project.userservice.model.ShippingAddress;

@Repository
public interface ShippingAddressRepository extends JpaRepository<ShippingAddress, Long> {

    // --- 비관적 잠금 적용 ---
    // 특정 사용자의 배송지 정보를 조회할 때 락을 걸어 동시 수정을 방지합니다.
    //@Lock(LockModeType.PESSIMISTIC_WRITE)   ->Transactional ReadOnly true 에서 사용 불가
    // JOIN FETCH를 사용하여 ShippingAddress와 User 엔티티를 즉시 로딩합니다.
    @Query("SELECT s FROM ShippingAddress s JOIN FETCH s.user u WHERE u.userId = :userId")
    List<ShippingAddress> findByUser_UserIdWithUser(@Param("userId") Long userId);
    
    //@Lock(LockModeType.OPTIMISTIC) // Transactional ReadOnly true 에서 사용 가능
    List<ShippingAddress> findByUser_UserId(Long userId);
    
    /**
     * 이 사용자의 기본 배송지.
     *
     * <p>🔴 예전에는 {@code Optional<ShippingAddress> findByActive(boolean)} 였다 —
     * <b>{@code user_id} 조건이 없는 전 테이블 조회</b>다. 결과가 두 가지로 나빴다:
     *
     * <ol>
     *   <li>사용자가 둘 이상 기본 배송지를 갖는 순간, {@code Optional} 로 선언된 쿼리가
     *       두 행을 만나 Spring Data 가 {@code IncorrectResultSizeDataAccessException} 을
     *       던진다 → 컨트롤러의 {@code catch (RuntimeException)} 에 걸려 400. 지금 사용자가
     *       사실상 한 명이라 드러나지 않았을 뿐, <b>두 번째 사용자가 배송지를 고르는 순간</b>
     *       재현된다.</li>
     *   <li>그 전이라도 — 전체에 active 가 딱 하나일 때 — 그 하나가 <b>다른 사람 것</b>이면
     *       남의 기본 배송지를 꺼 버린다.</li>
     * </ol>
     */
    Optional<ShippingAddress> findByUser_UserIdAndActiveTrue(Long userId);

    /**
     * 이 사용자의 배송지 하나.
     *
     * <p>🔴 소유권을 <b>쿼리에서</b> 확인한다. 예전에는 {@code findById(addressId)} 로 전체
     * 테이블에서 찾은 뒤 소유자를 대조하지 않고 그대로 지웠다 — 로그인한 아무나 주소 id 만
     * 알면 남의 배송지를 삭제할 수 있었다(IDOR).
     *
     * <p>"찾아서 비교한다" 가 아니라 "내 것 중에서 찾는다" 로 두는 이유: 비교를 빠뜨리면
     * 조용히 통과하지만, 조건이 쿼리에 있으면 빠뜨릴 수가 없다.
     */
    Optional<ShippingAddress> findByShippingAddressIdAndUser_UserId(Long shippingAddressId, Long userId);

    //@Lock(LockModeType.OPTIMISTIC)
    Optional<ShippingAddress> findById(Long id);

    /** 회원 탈퇴 최종 처리용. 배송지는 주소·연락처라 탈퇴하면 남길 이유가 없다. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true)
    @Query("delete from ShippingAddress s where s.user.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}