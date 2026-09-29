package com.project.catalog_service.message.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.listener.BatchListenerFailedException;

import com.project.common.message.dto.request.ProductUpdateRequest;

/**
 * DLQ 배치 실패 시맨틱 회귀 테스트 (docs/03 R-2).
 *
 * 리스너는 실패 레코드의 "배치 내 인덱스"를 BatchListenerFailedException 으로 알려야
 * DefaultErrorHandler 가 성공분 오프셋을 커밋하고 실패 레코드만 DLT 로 격리한다.
 * (기존 forEach 구현은 인덱스를 잃어 배치 전체가 통짜 재처리되었다)
 */
// LENIENT: strict stubbing은 "poison에만 스텁"된 메서드를 다른 인자(ok1)로 호출하는 순간
// PotentialStubbingProblem을 던져, 정상 레코드 처리를 실패로 오인시킨다.
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ProductUpdateKafkaListenerTest {

    @Mock ProductUpdateMessageListener productUpdateMessageListener;
    @org.mockito.Spy com.project.common.outbox.OutboxTraceContext outboxTraceContext =
            com.project.common.outbox.OutboxTraceContext.NOOP;
    @InjectMocks ProductUpdateKafkaListener listener;

    @Test
    @DisplayName("배치 중간 레코드 실패 시: 앞은 처리, 실패 인덱스 보고, 뒤는 미처리(재전달 대상)")
    void midBatchFailure_reportsFailingIndex() {
        ProductUpdateRequest ok1 = mock(ProductUpdateRequest.class);
        ProductUpdateRequest poison = mock(ProductUpdateRequest.class);
        ProductUpdateRequest ok2 = mock(ProductUpdateRequest.class);

        doThrow(new RuntimeException("boom")).when(productUpdateMessageListener).productUpdated(poison);

        Throwable thrown = catchThrowable(() -> listener.receive(List.of(ok1, poison, ok2), null));

        assertThat(thrown).isInstanceOf(BatchListenerFailedException.class);
        assertThat(((BatchListenerFailedException) thrown).getIndex()).isEqualTo(1);
        verify(productUpdateMessageListener).productUpdated(ok1);      // 성공분은 처리됨
        verify(productUpdateMessageListener, never()).productUpdated(ok2); // 실패 이후는 재전달로 처리
    }

    @Test
    @DisplayName("전부 성공하면 예외 없이 모든 레코드가 처리된다")
    void allSuccess_processesEverything() {
        ProductUpdateRequest m1 = mock(ProductUpdateRequest.class);
        ProductUpdateRequest m2 = mock(ProductUpdateRequest.class);

        listener.receive(List.of(m1, m2), null);

        verify(productUpdateMessageListener).productUpdated(m1);
        verify(productUpdateMessageListener).productUpdated(m2);
    }
}
