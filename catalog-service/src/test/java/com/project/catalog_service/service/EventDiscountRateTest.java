package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.catalog_service.dto.EventDtos.EventCategoryRequest;
import com.project.catalog_service.dto.EventDtos.EventProductRequest;
import com.project.catalog_service.dto.EventDtos.EventRequest;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * 기획전 할인율 0~100 — 요청 검증이 안쪽 목록까지 내려가고, 서비스도 한 번 더 막는다.
 *
 * <p>🐛 예전: {@code List<EventProductRequest>} 에 @Valid 가 없어 안쪽 @Max(100) 이 돌지 않았다 — 150% 가 저장되면 0원 판매.
 *
 * <p>[JA] 企画展の割引率 0〜100 — 検証が内側のリストまで届き、サービスも二重に止める。
 */
@DisplayName("기획전 할인율 — 0~100")
class EventDiscountRateTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static EventRequest request(List<EventProductRequest> products, List<EventCategoryRequest> categories) {
        return new EventRequest("여름", null, null, null, null, null, null, null, products, categories, null, null, null, null);
    }

    @Test
    @DisplayName("🔴 상품 · 카테고리 목록 안의 할인율 150 은 요청 검증에서 걸린다")
    void nestedRatesAreValidated() {
        assertThat(validator.validate(request(List.of(new EventProductRequest(1L, 150)), null)))
                .anyMatch(v -> v.getPropertyPath().toString().contains("discountRate"));
        assertThat(validator.validate(request(null, List.of(new EventCategoryRequest(1L, -1)))))
                .anyMatch(v -> v.getPropertyPath().toString().contains("discountRate"));
        assertThat(validator.validate(request(List.of(new EventProductRequest(1L, 100)), List.of(new EventCategoryRequest(1L, 0)))))
                .isEmpty();
    }

    @Test
    @DisplayName("🔴 서비스도 0~100 밖을 거절한다 — 검증을 거치지 않은 호출에도")
    void serviceRejectsOutOfRange() {
        assertThat(EventService.checkedRate(0)).isZero();
        assertThat(EventService.checkedRate(100)).isEqualTo(100);
        assertThatThrownBy(() -> EventService.checkedRate(101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventService.checkedRate(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventService.checkedRate(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
