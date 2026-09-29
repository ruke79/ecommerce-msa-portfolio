package com.project.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 응답 본문에 싣는 예외 문구 — 우리가 사람에게 쓴 문구만 (docs/57 §1).
 */
@DisplayName("ClientMessage — 예외 문구를 응답에 실어도 되는가")
class ClientMessageTest {

    private static final String SQL_MESSAGE =
            "could not execute statement [ERROR: duplicate key value violates unique constraint \"uk_coupon_user\""
                    + " Detail: Key (user_id, coupon_id)=(42, 7) already exists.] [insert into coupons.user_coupon ...]";

    @Test
    @DisplayName("우리 코드가 문구를 담아 던진 표준 예외는 그대로 보인다")
    void ownPlainMessages() {
        assertThat(ClientMessage.of(new RuntimeException("사용 기간이 지난 쿠폰입니다."))).isEqualTo("사용 기간이 지난 쿠폰입니다.");
        assertThat(ClientMessage.of(new IllegalArgumentException("이름을 입력해 주세요."))).isEqualTo("이름을 입력해 주세요.");
        assertThat(ClientMessage.of(new com.project.common.domain.exception.StateConflictException("이미 환불된 주문입니다.")))
                .isEqualTo("이미 환불된 주문입니다.");
        // 🔒 IllegalStateException 은 운영자용 문구에 쓴다 — 내보내지 않는다(docs/57 §15).
        assertThat(ClientMessage.of(new IllegalStateException("SKU 생성이 10회 연속 충돌했습니다 — product_sku_seq 시퀀스 상태를 확인하세요")))
                .isEqualTo(ClientMessage.DEFAULT);
        assertThat(ClientMessage.of(new NoSuchElementException("주문이 없습니다."))).isEqualTo("주문이 없습니다.");
    }

    @Test
    @DisplayName("com.project 의 도메인 예외는 보인다")
    void domainExceptions() {
        assertThat(ClientMessage.of(new CustomException(ErrorCode.USER_NOT_FOUND)))
                .isEqualTo("해당 회원 정보를 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("🔴 JDBC·JPA 예외의 문구(SQL·제약 이름·입력값)는 보이지 않는다")
    void dataAccessHidden() {
        String shown = ClientMessage.of(new DataIntegrityViolationException(SQL_MESSAGE));

        assertThat(shown).isEqualTo(ClientMessage.DEFAULT);
        assertThat(shown).doesNotContain("constraint", "insert", "42");
    }

    @Test
    @DisplayName("🔴 JVM 의 NPE 문구(코드 구조)는 보이지 않는다")
    void npeHidden() {
        String npe = "Cannot invoke \"com.project.order_service.model.Order.getTotal()\" because \"order\" is null";

        assertThat(ClientMessage.of(new NullPointerException(npe))).isEqualTo(ClientMessage.DEFAULT);
    }

    @Test
    @DisplayName("🔴 허용된 예외라도 다른 예외를 감싸기만 했다면(new RuntimeException(e)) 원인의 문구다 — 숨긴다")
    void wrappedHidden() {
        RuntimeException wrapped = new RuntimeException(new SQLException(SQL_MESSAGE));

        assertThat(ClientMessage.of(wrapped)).isEqualTo(ClientMessage.DEFAULT);
    }

    @Test
    @DisplayName("원인이 있어도 우리가 문구를 따로 썼으면 그 문구는 보인다")
    void ownMessageWithCause() {
        RuntimeException e = new RuntimeException("결제 확인에 실패했습니다.", new SQLException(SQL_MESSAGE));

        assertThat(ClientMessage.of(e)).isEqualTo("결제 확인에 실패했습니다.");
    }

    @Test
    @DisplayName("표준 예외의 하위 클래스는 허용 목록이 아니다 — 라이브러리 예외 대부분이 그렇다")
    void subclassesAreNotPlain() {
        assertThat(ClientMessage.of(new java.util.ConcurrentModificationException("internal")))
                .isEqualTo(ClientMessage.DEFAULT);
        assertThat(ClientMessage.of(new NumberFormatException("For input string: \"abc\"")))
                .isEqualTo(ClientMessage.DEFAULT);
    }

    @Test
    @DisplayName("문구가 없거나 비었으면 대체 문구")
    void emptyMessages() {
        assertThat(ClientMessage.of(new RuntimeException())).isEqualTo(ClientMessage.DEFAULT);
        assertThat(ClientMessage.of(new RuntimeException("  "), "대체")).isEqualTo("대체");
        assertThat(ClientMessage.of(null, "대체")).isEqualTo("대체");
    }
}
