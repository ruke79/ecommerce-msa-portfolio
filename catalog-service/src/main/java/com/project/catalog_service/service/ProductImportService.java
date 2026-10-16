package com.project.catalog_service.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.stereotype.Service;

import com.project.catalog_service.service.ImageService.StorageStatus;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * CSV 일괄 상품 등록.
 *
 * <p>세 가지를 고친다.
 *
 * <p><b>1. 동기 실행.</b> 예전 {@code POST /api/products/load} 는 {@code jobLauncher.run}
 * 을 요청 스레드에서 그대로 돌렸다. 부트의 기본 런처는 {@code SyncTaskExecutor} 라
 * 잡이 끝날 때까지 응답이 나가지 않는다 — 이 잡은 이미지 60장을 S3 로 올리므로 몇 분이
 * 걸리고, 게이트웨이·터널 타임아웃에 먼저 걸린다. 관리자는 504 를 보지만 잡은 계속
 * 돌고 있다. 그래서 "실패했나?" 싶어 다시 누르면 <b>두 번 들어간다.</b>
 * 지금은 즉시 202 로 돌려주고 진행 상황을 따로 묻는다.
 *
 * <p><b>2. 중복 실행.</b> 이 잡은 멱등하지 않다(엔티티를 새로 만들어 넣는다).
 * 동시에 두 번 돌면 상품이 두 벌 생긴다. 한 번에 하나만 돌도록 막는다.
 *
 * <p><b>3. 시작 전 저장소 점검.</b> 이미지 업로드가 실패하면 이제 잡이 실패한다
 * (ImageService.uploadClasspathImage). 자격 증명이 죽어 있으면 카테고리·상품까지
 * 절반 들어간 뒤 SKU 스텝에서 죽는다는 뜻이다. 시작 전에 쓰기 한 번으로 확인하면
 * 그 절반을 만들지 않는다.
 *
 * <p><b>파드 사이의 잠금</b>({@link ImportLock}, Redis). 🐛 예전에는 {@code AtomicBoolean} 하나뿐이라 레플리카 2개
 * (k8s/41)에서 다시 누른 요청이 다른 파드로 가면 임포트가 동시에 두 번 돌았다 — 둘 다 "비어 있음" 을 통과해 카탈로그가
 * 두 벌이 되거나, reset 쪽이 다른 쪽이 넣는 중인 데이터를 지웠다. 진행 상황(status)은 여전히 요청을 받은 파드의 것이다.
 *
 * <p>[JA] ポッド間のロック(Redis)。以前は AtomicBoolean だけで、別ポッドに届いた再実行が同時に走った。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImportService {

    public static final String JOB_NAME = "productImportJob";

    private final JobLauncher jobLauncher;
    private final Map<String, Job> allJobs;
    private final ImageService imageService;
    private final CatalogResetService catalogResetService;
    private final ImportLock importLock;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ImportStatus> last = new AtomicReference<>(ImportStatus.idle());

    // 데몬 스레드 하나. 잡이 도는 중에 파드가 내려가면 어차피 중단되고, 데몬이라
    // 종료를 붙잡지 않는다(잡 상태는 BATCH_JOB_EXECUTION 에 남는다).
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "product-import");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** 지금 상태. 진행 중이면 RUNNING, 아니면 마지막 실행 결과. */
    public ImportStatus status() {
        return last.get();
    }

    /** 저장소가 쓰기 가능한지. 등록 화면이 폼을 그리기 전에 묻는다. */
    public StorageStatus storage() {
        return imageService.probe();
    }

    /**
     * 임포트 시작. <b>즉시 돌아온다.</b>
     *
     * @throws AlreadyRunningException  이미 돌고 있을 때
     * @throws StorageNotWritableException 이미지 저장소에 쓸 수 없을 때
     * @throws IllegalStateException    잡 빈이 없을 때
     */
    public ImportStatus start(boolean reset) {
        Job job = allJobs.get(JOB_NAME);
        if (job == null) {
            throw new IllegalStateException("임포트 잡을 찾을 수 없습니다: " + JOB_NAME);
        }

        if (!running.compareAndSet(false, true)) {
            throw new AlreadyRunningException();
        }
        if (!importLock.tryAcquire()) {
            running.set(false);
            throw new AlreadyRunningException();   // 다른 파드에서 돌고 있다
        }

        try {
            // 🔒 저장소부터 본다 — 🐛 예전에는 reset(TRUNCATE … CASCADE)이 먼저였다. S3 자격 증명이 만료된 채 reset 을 누르면
            //    카탈로그 전체가 지워진 뒤 503 만 돌아오고 임포트는 시작되지 않았다(되돌릴 수 없다).
            // [JA] ストレージを先に確認する。以前は reset が先で、資格情報が切れていると全カタログが消えた後に 503 だった。
            StorageStatus storage = imageService.probe();
            if (!storage.writable()) {
                throw new StorageNotWritableException(storage.detail());
            }

            // ⚠️ 이 잡은 멱등하지 않다 — 이미 들어간 것 위에 또 넣는다. 그래서 카탈로그가
            //    비어 있지 않으면 **시작하지 않는다.** 예전에는 그냥 돌았고, 실패한 임포트를
            //    다시 누른 운영자가 카테고리 5→10 · 하위 15→30 을 만든 뒤 상품에서
            //    uq_product_slug 에 걸려 멈췄다. 유니크 제약이 없었다면 조용히 두 벌이 됐다.
            //
            //    reset=true 면 비우고 시작한다(CatalogResetService — 되돌릴 수 없다).
            //    시퀀스까지 1 로 되돌려야 하는 이유는 그 클래스 주석에 있다.
            long rows = catalogResetService.countCatalogRows();
            if (rows > 0) {
                if (!reset) {
                    throw new CatalogNotEmptyException(rows);
                }
                catalogResetService.reset();
            }
        } catch (RuntimeException e) {
            importLock.release();
            running.set(false);
            throw e;
        }

        ImportStatus started = ImportStatus.running(LocalDateTime.now());
        last.set(started);
        // 가져오기를 시작한 관리자 요청의 traceId·userId 를 작업 스레드로 넘긴다(docs/51 §3).
        executor.submit(com.project.common.logging.MdcTaskDecorator.wrap(() -> run(job)));
        log.info("상품 일괄 임포트를 시작합니다.");
        return started;
    }

    private void run(Job job) {
        LocalDateTime startedAt = last.get().startedAt();
        try {
            // time 파라미터로 매번 새 인스턴스가 된다 — 같은 파라미터면 Spring Batch 가
            // 재실행을 거부한다(JobInstanceAlreadyCompleteException).
            JobParameters parameters = new JobParametersBuilder()
                    .addLong("time", System.currentTimeMillis())
                    .toJobParameters();

            JobExecution execution = jobLauncher.run(job, parameters);

            List<StepResult> steps = execution.getStepExecutions().stream()
                    .map(StepResult::of)
                    .toList();

            // 실패 원인은 예외 타입까지 남긴다. "이미지 업로드 실패: images/food/Image_2.jpg"
            // 같은 메시지가 그대로 관리자 화면에 보여야 원인을 짚을 수 있다.
            String message = execution.getAllFailureExceptions().isEmpty()
                    ? null
                    : execution.getAllFailureExceptions().get(0).getMessage();

            boolean ok = !execution.getStatus().isUnsuccessful();
            last.set(new ImportStatus(ok ? "COMPLETED" : "FAILED", startedAt,
                    LocalDateTime.now(), message, steps));
            if (ok) {
                log.info("상품 일괄 임포트 완료: {}", steps);
            } else {
                log.error("상품 일괄 임포트 실패: {} ({})", message, steps);
            }
        } catch (Exception e) {
            log.error("상품 일괄 임포트가 시작되지 못했습니다.", e);
            last.set(new ImportStatus("FAILED", startedAt, LocalDateTime.now(),
                    e.getMessage(), List.of()));
        } finally {
            try {
                catalogResetService.clearCaches();
            } catch (RuntimeException e) {
                log.warn("가져오기 뒤 캐시를 비우지 못했다 — TTL 안에 풀린다 ({})", e.getClass().getSimpleName());
            }
            importLock.release();
            running.set(false);
        }
    }

    // ── 응답 타입 ────────────────────────────────────────────────────────

    /**
     * @param state IDLE / RUNNING / COMPLETED / FAILED
     */
    public record ImportStatus(String state, LocalDateTime startedAt, LocalDateTime finishedAt,
                               String message, List<StepResult> steps) {

        static ImportStatus idle() {
            return new ImportStatus("IDLE", null, null, null, List.of());
        }

        static ImportStatus running(LocalDateTime startedAt) {
            return new ImportStatus("RUNNING", startedAt, null, null, List.of());
        }
    }

    /** 스텝별 결과. 어느 스텝에서 몇 건 만에 멈췄는지가 원인 추적의 시작점이다. */
    public record StepResult(String name, String status, long read, long written, long skipped) {

        static StepResult of(StepExecution s) {
            return new StepResult(s.getStepName(), s.getStatus().name(),
                    s.getReadCount(), s.getWriteCount(), s.getSkipCount());
        }
    }

    // ── 예외 ────────────────────────────────────────────────────────────

    /** 이미 돌고 있다. 두 번 돌리면 상품이 두 벌 생긴다. */
    public static class AlreadyRunningException extends RuntimeException {
        public AlreadyRunningException() {
            super("이미 임포트가 진행 중입니다.");
        }
    }

    /**
     * 카탈로그가 이미 차 있다. 이 잡은 멱등하지 않아 그대로 돌면 두 벌이 된다.
     *
     * <p>메시지에 다음 행동을 적는다 — 운영자가 화면만 보고 무엇을 해야 하는지 알아야 한다.
     */
    public static class CatalogNotEmptyException extends RuntimeException {
        public CatalogNotEmptyException(long rows) {
            super("카탈로그에 이미 데이터가 있습니다(카테고리+하위카테고리+상품 " + rows + "행). "
                    + "이 임포트는 멱등하지 않아 그대로 돌리면 두 벌이 됩니다. "
                    + "기존 카탈로그를 버리고 다시 넣으려면 reset=true 로 호출하세요.");
        }
    }

    /** 이미지 저장소에 쓸 수 없다. 이 상태로 시작하면 데이터가 절반만 들어간다. */
    public static class StorageNotWritableException extends RuntimeException {
        public StorageNotWritableException(String detail) {
            super("이미지 저장소에 쓸 수 없습니다: " + (detail == null ? "원인 미상" : detail));
        }
    }
}
