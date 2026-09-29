package com.project.catalog_service.repository.jpa.data;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import com.project.catalog_service.model.ProductColor;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

@Repository
public interface ProductColorRepository extends JpaRepository<ProductColor, Long> {

    // --- 비관적 잠금 적용 ---
    //@Lock(LockModeType.PESSIMISTIC_WRITE)
    // JpaRepository의 findById 메서드를 오버라이드하여 락을 적용할 수 있습니다.
    // 여기서는 예시로 작성하며, 실제로는 Service 계층에서 @Transactional을 사용해야 합니다.
    Optional<ProductColor> findById(Long id);
    
     // 낙관적 잠금 적용
    // @Version 필드를 사용하는 낙관적 잠금은 findById() 메서드 호출 시,
    // Service 계층의 @Transactional 환경에서 엔티티가 수정될 때 자동으로 동작합니다.
    // 명시적 락이 필요하다면 아래와 같이 새로운 메서드를 정의할 수 있습니다.
    //@Lock(LockModeType.OPTIMISTIC)
    Optional<ProductColor> findByColor(String color);
}