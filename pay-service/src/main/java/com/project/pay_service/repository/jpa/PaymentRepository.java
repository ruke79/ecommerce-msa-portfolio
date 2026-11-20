package com.project.pay_service.repository.jpa;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import com.project.pay_service.model.Payment;

import jakarta.persistence.LockModeType;




@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    // findByTrackingId에도 비관적 잠금 적용 가능
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    // [JA] findByTrackingId にも悲観的ロックを適用できる
    Optional<Payment> findByTrackingId(String trackingId);


    // 비관적 잠금 적용 (WRITE 잠금)
    // 이 메서드를 호출하면 데이터베이스에서 해당 행에 잠금을 겁니다.
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    // [JA] 悲観的ロックを適用(WRITE ロック)
    //      このメソッドを呼ぶとデータベースで該当行にロックをかける。
    Optional<Payment> findById(Long id);
    
    

}
