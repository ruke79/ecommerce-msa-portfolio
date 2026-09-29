package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.JobLauncher;

import com.project.catalog_service.service.ImageService.StorageStatus;
import com.project.catalog_service.service.ProductImportService.AlreadyRunningException;
import com.project.catalog_service.service.ProductImportService.StorageNotWritableException;

/**
 * CSV 일괄 임포트의 기동 규칙.
 *
 * <p>세 가지를 못 박는다: 저장소가 안 되면 <b>시작하지 않는다</b>, 두 번 돌지 않는다,
 * 요청 스레드를 붙잡지 않는다. 셋 다 예전에 실제로 사고가 났던 지점이다.
 */
class ProductImportServiceTest {

    private static final StorageStatus OK = new StorageStatus(true, "bucket", null);
    private static final StorageStatus DEAD =
            new StorageStatus(false, "bucket", "The AWS Access Key Id you provided does not exist");

    private final JobLauncher jobLauncher = mock(JobLauncher.class);
    private final ImageService imageService = mock(ImageService.class);
    private final Job job = mock(Job.class);

    private ProductImportService service() {
        return new ProductImportService(jobLauncher,
                Map.of(ProductImportService.JOB_NAME, job), imageService, emptyCatalog());
    }

    @Test
    void startsIdle() {
        assertThat(service().status().state()).isEqualTo("IDLE");
    }

    /**
     * 저장소에 쓸 수 없으면 <b>잡을 띄우지도 않는다.</b>
     *
     * <p>업로드 실패가 이제 스텝을 실패시키므로, 자격 증명이 죽은 채 시작하면 카테고리와
     * 상품까지 들어간 뒤 SKU 스텝에서 죽는다 — 절반만 채워진 DB 가 남는다.
     */
    @Test
    void refusesToStartWhenStorageIsNotWritable() throws Exception {
        when(imageService.probe()).thenReturn(DEAD);
        ProductImportService service = service();

        assertThatThrownBy(() -> service.start(false))
                .isInstanceOf(StorageNotWritableException.class)
                .hasMessageContaining("Access Key");

        verify(jobLauncher, never()).run(any(), any());
        // 실패했으니 잠금이 풀려 있어야 한다 — 여기서 안 풀면 키를 고친 뒤에도
        // 영원히 "이미 진행 중"이 된다.
        assertThat(service.status().state()).isEqualTo("IDLE");
    }

    @Test
    void rejectsSecondStartWhileRunning() throws Exception {
        when(imageService.probe()).thenReturn(OK);

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(jobLauncher.run(any(Job.class), any(JobParameters.class))).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return completed();
        });

        ProductImportService service = service();
        assertThat(service.start(false).state()).isEqualTo("RUNNING");
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        // 이 잡은 멱등하지 않다 — 두 번 돌면 상품이 두 벌 생긴다.
        assertThatThrownBy(() -> service.start(false)).isInstanceOf(AlreadyRunningException.class);

        release.countDown();
        assertThat(awaitState(service, "COMPLETED")).isEqualTo("COMPLETED");
    }

    /** 잡이 실패하면 원인이 상태에 남아야 한다 — 관리자가 로그를 볼 수 없는 경우가 대부분이다. */
    @Test
    void recordsFailureMessage() throws Exception {
        when(imageService.probe()).thenReturn(OK);

        JobExecution execution = mock(JobExecution.class);
        when(execution.getStepExecutions()).thenReturn(List.of());
        when(execution.getStatus()).thenReturn(BatchStatus.FAILED);
        when(execution.getAllFailureExceptions())
                .thenReturn(List.of(new IllegalStateException("이미지 업로드 실패: images/food/Image_2.jpg")));
        when(jobLauncher.run(any(Job.class), any(JobParameters.class))).thenReturn(execution);

        ProductImportService service = service();
        service.start(false);

        assertThat(awaitState(service, "FAILED")).isEqualTo("FAILED");
        assertThat(service.status().message()).contains("Image_2.jpg");
    }

    @Test
    void failsWhenJobBeanIsMissing() {
        ProductImportService service =
                new ProductImportService(jobLauncher, Map.of(), imageService, emptyCatalog());

        assertThatThrownBy(() -> service.start(false)).isInstanceOf(IllegalStateException.class);
    }

    // ── 도우미 ──────────────────────────────────────────────────────────

    private JobExecution completed() {
        JobExecution execution = mock(JobExecution.class);
        when(execution.getStepExecutions()).thenReturn(List.of());
        when(execution.getStatus()).thenReturn(BatchStatus.COMPLETED);
        when(execution.getAllFailureExceptions()).thenReturn(List.of());
        return execution;
    }

    /** 임포트는 다른 스레드에서 돈다. 상태가 바뀔 때까지 잠깐 기다린다. */
    private String awaitState(ProductImportService service, String expected)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (expected.equals(service.status().state())) {
                return expected;
            }
            Thread.sleep(20);
        }
        return service.status().state();
    }

    /**
     * 빈 카탈로그를 돌려주는 목. 이 테스트들은 "재실행 방지"가 아니라 <b>동기/중복/저장소</b>
     * 를 검증하므로, 카탈로그 상태는 항상 비어 있는 것으로 둔다
     * (재실행 방지는 ProductImportGuardTest 가 본다).
     */
    private static CatalogResetService emptyCatalog() {
        CatalogResetService reset = org.mockito.Mockito.mock(CatalogResetService.class);
        org.mockito.Mockito.when(reset.countCatalogRows()).thenReturn(0L);
        return reset;
    }
}
