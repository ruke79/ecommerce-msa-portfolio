package com.project.catalog_service.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;

import com.project.catalog_service.service.ImageService.StorageStatus;
import com.project.catalog_service.service.ProductImportService.AlreadyRunningException;
import com.project.catalog_service.service.ProductImportService.CatalogNotEmptyException;
import com.project.catalog_service.service.ProductImportService.StorageNotWritableException;

/**
 * 임포트를 <b>다시 눌렀을 때</b> 무슨 일이 나는가.
 *
 * <p>이 잡은 멱등하지 않다. 프로세서가 조회 없이 새 엔티티를 만들고, 실행마다 {@code time}
 * 파라미터로 새 JobInstance 가 되어 스프링 배치의 재시작이 동작하지 않는다 — 언제나 1단계부터
 * 다시 돈다.
 *
 * <p>실제로 이렇게 됐다: S3 권한 때문에 이미지 업로드에서 죽은 뒤 다시 누르자 카테고리 5→10,
 * 하위 카테고리 15→30 이 되고 상품에서 {@code uq_product_slug} 에 걸려 멈췄다. 유니크 제약이
 * 없었다면 상품 500개짜리 카탈로그가 <b>조용히</b> 만들어졌을 것이다. 운영자는 psql 로 직접
 * TRUNCATE 해야 했다.
 *
 * <p>그래서 이제 카탈로그가 비어 있지 않으면 <b>시작하지 않는다.</b>
 */
class ProductImportGuardTest {

    private final JobLauncher jobLauncher = mock(JobLauncher.class);
    private final Job job = mock(Job.class);
    private final ImageService imageService = mock(ImageService.class);
    private final CatalogResetService catalogResetService = mock(CatalogResetService.class);

    private final ProductImportService service = new ProductImportService(
            jobLauncher, Map.of(ProductImportService.JOB_NAME, job), imageService, catalogResetService, ImportLock.LOCAL);

    @Test
    @DisplayName("카탈로그가 비어 있지 않은데 reset 없이 시작하면 거절한다 — 두 벌이 되는 것을 막는다")
    void refusesWhenCatalogNotEmpty() {
        when(catalogResetService.countCatalogRows()).thenReturn(270L);
        when(imageService.probe()).thenReturn(new StorageStatus(true, "bucket", null));

        assertThatThrownBy(() -> service.start(false))
                .isInstanceOf(CatalogNotEmptyException.class)
                .hasMessageContaining("270")
                .hasMessageContaining("reset=true");

        // 비우지 않는다 — 시작 자체를 안 한다(저장소 점검은 그보다 먼저 한다 — 아래).
        verify(catalogResetService, never()).reset();
    }

    /** 🐛 예전에는 reset(TRUNCATE … CASCADE)이 저장소 점검보다 먼저였다 — 자격 증명이 만료된 채 누르면 카탈로그가 지워진 뒤 503. */
    @Test
    @DisplayName("🔴 저장소에 쓸 수 없으면 reset=true 라도 카탈로그를 비우지 않는다 — 비우기 전에 점검한다")
    void storageCheckedBeforeReset() {
        when(catalogResetService.countCatalogRows()).thenReturn(270L);
        when(imageService.probe()).thenReturn(new StorageStatus(false, "bucket", "AccessDenied"));

        assertThatThrownBy(() -> service.start(true)).isInstanceOf(StorageNotWritableException.class);

        verify(catalogResetService, never()).reset();
    }

    /** 🐛 예전에는 파드 안의 AtomicBoolean 뿐이라 다른 파드로 간 재실행이 동시에 돌았다(두 벌 · 진행 중 데이터 TRUNCATE). */
    @Test
    @DisplayName("🔴 다른 파드가 임포트 잠금을 쥐고 있으면 시작하지 않는다 — 비우지도 않는다")
    void otherPodHoldsTheLock() {
        ImportLock held = new ImportLock() {
            @Override
            public boolean tryAcquire() {
                return false;
            }

            @Override
            public void release() {
            }
        };
        ProductImportService other = new ProductImportService(
                jobLauncher, Map.of(ProductImportService.JOB_NAME, job), imageService, catalogResetService, held);
        when(catalogResetService.countCatalogRows()).thenReturn(270L);

        assertThatThrownBy(() -> other.start(true)).isInstanceOf(AlreadyRunningException.class);
        verify(catalogResetService, never()).reset();
    }

    @Test
    @DisplayName("거절하면 잠금을 푼다 — 남으면 2시간 동안 아무 파드도 시작하지 못한다")
    void refusalReleasesTheLock() {
        java.util.concurrent.atomic.AtomicInteger released = new java.util.concurrent.atomic.AtomicInteger();
        ImportLock counting = new ImportLock() {
            @Override
            public boolean tryAcquire() {
                return true;
            }

            @Override
            public void release() {
                released.incrementAndGet();
            }
        };
        ProductImportService svc = new ProductImportService(
                jobLauncher, Map.of(ProductImportService.JOB_NAME, job), imageService, catalogResetService, counting);
        when(imageService.probe()).thenReturn(new StorageStatus(false, "bucket", "x"));

        assertThatThrownBy(() -> svc.start(false)).isInstanceOf(StorageNotWritableException.class);
        assertThat(released.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("거절한 뒤에는 다시 시작할 수 있어야 한다 — running 플래그가 잠긴 채 남으면 안 된다")
    void refusalReleasesTheRunningFlag() {
        when(catalogResetService.countCatalogRows()).thenReturn(270L);
        when(imageService.probe()).thenReturn(new StorageStatus(true, "bucket", null));
        assertThatThrownBy(() -> service.start(false)).isInstanceOf(CatalogNotEmptyException.class);

        // 두 번째 호출이 AlreadyRunning 이면 플래그가 안 풀린 것이다(그러면 파드를 재시작해야 한다).
        assertThatThrownBy(() -> service.start(false))
                .isInstanceOf(CatalogNotEmptyException.class)
                .isNotInstanceOf(AlreadyRunningException.class);
    }

    @Test
    @DisplayName("reset=true 면 비우고 시작한다")
    void resetClearsThenStarts() {
        when(catalogResetService.countCatalogRows()).thenReturn(270L);
        when(imageService.probe()).thenReturn(new StorageStatus(true, "bucket", null));

        assertThat(service.start(true).state()).isEqualTo("RUNNING");

        verify(catalogResetService).reset();
    }

    @Test
    @DisplayName("카탈로그가 비어 있으면 reset 없이도 시작한다 — 첫 임포트를 막으면 안 된다")
    void emptyCatalogStartsWithoutReset() {
        when(catalogResetService.countCatalogRows()).thenReturn(0L);
        when(imageService.probe()).thenReturn(new StorageStatus(true, "bucket", null));

        assertThat(service.start(false).state()).isEqualTo("RUNNING");

        verify(catalogResetService, never()).reset();
    }

    @Test
    @DisplayName("저장소가 쓰기 불가면 시작하지 않는다 — 절반만 들어간 카탈로그를 만들지 않는다")
    void refusesWhenStorageNotWritable() {
        when(catalogResetService.countCatalogRows()).thenReturn(0L);
        when(imageService.probe()).thenReturn(new StorageStatus(false, "bucket", "AccessDenied"));

        assertThatThrownBy(() -> service.start(false))
                .isInstanceOf(StorageNotWritableException.class);
    }
}
