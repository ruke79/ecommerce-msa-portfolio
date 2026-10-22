package com.project.catalog_service.controller;

import java.util.List;
import java.util.Map;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.project.catalog_service.document.CategoryDocument;
import com.project.catalog_service.dto.SearchResultDto;
import com.project.common.dto.catalog.request.SearchParamsRequest;
import com.project.catalog_service.model.Product;
import com.project.catalog_service.repository.elasticsearch.CategoryDocumentRepository;
import com.project.catalog_service.service.ProductService;
import com.project.catalog_service.service.SearchService;
import com.project.common.response.GenericResponse;

import lombok.RequiredArgsConstructor;

@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("api/")
public class SearchController {

    private final SearchService searchService;
    /** 카드의 재고 표시 — 지금 수량 − 결제 전 예약(docs/59 §5). 결과는 캐시(search_cache)에서 오므로 응답 직전에. */
    private final com.project.catalog_service.service.StockAvailability stockAvailability;
    private final CategoryDocumentRepository categoryDocumentRepository;

    private final JobLauncher jobLauncher;
    // @Qualifier("productIndexingJob") // 이 라인을 추가
    // private final Job productIndexingJob;
    private final Map<String, Job> allJobs; // List<Job> 대신 Map<String, Job>을 사용하여 Job 이름으로 특정 Job을 주입

    /** 첫 쪽의 필터 메뉴 — 결과 캐시 밖에서 붙인다(계산은 요청 밖, 기다림은 짧게 — SearchFilterFacets). */
    private final com.project.catalog_service.service.SearchFilterFacets filterFacets;

    /** 이 검색을 ES 로 할지 — 기능 플래그(docs/74). 캐시 밖에서 정해 캐시 키에 넣는다. */
    private final com.project.catalog_service.service.SearchEngineSelector searchEngineSelector;

    /** 한 번에 하나만. 싱글턴 리더를 두 실행이 나눠 읽으면 색인이 조용히 망가진다. */
    private final java.util.concurrent.atomic.AtomicBoolean indexing =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // 데몬 스레드 하나 — 파드가 내려갈 때 종료를 붙잡지 않는다(잡 상태는 메타 테이블에 남는다).
    private final java.util.concurrent.ExecutorService indexingExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "es-indexing");
                t.setDaemon(true);
                return t;
            });

    @jakarta.annotation.PreDestroy
    void shutdownIndexingExecutor() {
        indexingExecutor.shutdownNow();
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchProducts(SearchParamsRequest params) {

        // 엔진은 캐시(@Cacheable) 밖에서 정한다 — 여기서 평가해야 이 요청의 서버 스팬에 변형이 남고(docs/74),
        // 캐시가 맞아도 매 요청 기록된다. [JA] エンジンはキャッシュの外で決める — ここで評価すればこのリクエストの
        // サーバースパンに変形が残り、キャッシュヒットでも毎回記録される。
        SearchResultDto dto = searchService.searchProducts(params, searchEngineSelector.select(params));
        dto = filterFacets.attach(dto, params);

        return new ResponseEntity<>(withStock(dto), HttpStatus.OK);

    }

    /**
     * 검색 질의가 시간 상한({@code catalog.search.query-timeout-seconds}, 기본 5초)에 걸렸다 — 503. 질의는 DB 에서 취소돼 커넥션이 바로 돌아간다.
     *
     * <p>🐛 2026-10-20 실습(상품 1,000만, docs/83 §2.8): 상한이 없어 필터 검색 하나가 75 ~ 195초 동안 커넥션을 쥐었다. 게이트웨이는 10초에
     * 포기하지만 catalog 의 스레드와 커넥션은 질의가 끝날 때까지 묶여, 파드마다 5개뿐인 풀이 차자 home · banners 까지 5초 대기 끝에 5xx 였다
     * (필터 검색 측정 실패 95.04%). 이제 비싼 검색만 실패하고 나머지는 산다.
     *
     * <p>[JA] 検索照会が時間上限に掛かった — 503。照会は DB で取り消され、接続はすぐ戻る。以前は上限がなく、一件が接続を 3 分握って全体が 5xx だった。
     */
    @org.springframework.web.bind.annotation.ExceptionHandler({
            org.springframework.dao.DataAccessException.class, jakarta.persistence.PersistenceException.class,
            org.springframework.transaction.TransactionException.class, org.hibernate.HibernateException.class })
    public ResponseEntity<String> searchTimedOut(RuntimeException e) {
        if (!isQueryTimeout(e)) {
            throw e;
        }
        log.warn("검색 질의 시간 초과 — 503 ({})", e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("검색이 오래 걸려 중단했습니다. 잠시 뒤 다시 시도해 주세요.");
    }

    /**
     * 검색 격벽이 찼다 — 커넥션을 잡기 전에 503 · {@code Retry-After: 2}. 비싼 검색이 몰려도 풀의 나머지는 home · 상품 쪽 몫으로 남는다
     * ({@link com.project.catalog_service.service.SearchBulkhead}, docs/83 §2.8).
     *
     * <p>[JA] 検索の隔壁が満杯 — 接続を取る前に 503。高い検索が集中してもプールの残りは home・商品側に残る。
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(com.project.catalog_service.service.SearchBulkhead.Busy.class)
    public ResponseEntity<String> searchBusy(com.project.catalog_service.service.SearchBulkhead.Busy e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(org.springframework.http.HttpHeaders.RETRY_AFTER, "2")
                .body("검색 요청이 몰려 잠시 받지 못했습니다. 잠시 뒤 다시 시도해 주세요.");
    }

    /** 시간 상한으로 끝난 질의인가 — 번역 계층마다 예외 종류가 달라 원인 사슬을 본다(PostgreSQL 은 SQLSTATE 57014). */
    public static boolean isQueryTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof org.springframework.dao.QueryTimeoutException
                    || t instanceof jakarta.persistence.QueryTimeoutException
                    || t instanceof org.hibernate.QueryTimeoutException
                    || t instanceof org.springframework.transaction.TransactionTimedOutException
                    || (t instanceof java.sql.SQLException sql && "57014".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }


    /** 결과 페이지의 상품에 지금 재고를 반영한 복사본 — 캐시된 결과는 그대로 둔다. */
    private SearchResultDto withStock(SearchResultDto dto) {
        if (dto == null || dto.getProduct() == null || dto.getProduct().getContent().isEmpty()) {
            return dto;
        }
        org.springframework.data.domain.Page<com.project.common.dto.ProductDto> page = dto.getProduct();
        return dto.toBuilder()
                .product(new org.springframework.data.domain.PageImpl<>(
                        stockAvailability.apply(page.getContent()), page.getPageable(), page.getTotalElements()))
                .build();
    }

    /**
     * 전체 재색인 기동.
     *
     * <p>두 가지를 고쳤다.
     *
     * <p><b>1. 요청 스레드에서 동기로 돌았다.</b> 부트의 기본 런처는 {@code SyncTaskExecutor}
     * 라 {@code jobLauncher.run} 이 잡이 끝날 때까지 돌아오지 않는다. 상품 전량을 ES 로
     * 색인하는 동안 응답이 나가지 않으므로 게이트웨이 타임아웃(10초)·프론트 타임아웃(15초)에
     * 먼저 걸린다. 관리자는 실패로 보지만 잡은 계속 돈다 — 그래서 다시 누른다.
     *
     * <p><b>2. 중복 실행을 막지 않았다.</b> 리더 빈들이 {@code @StepScope} 없는 싱글턴이라
     * (ElasticsearchBatchConfig) 같은 잡이 동시에 둘 돌면 <b>같은 리더 인스턴스</b>를 나눠
     * 읽는다. 읽기 위치가 뒤엉켜 색인이 중간부터 비거나 {@code ReaderNotOpenException} 이
     * 난다. ProductImportService 와 같은 이유·같은 방식으로 한 번에 하나만 돌린다.
     *
     * <p>202 로 즉시 돌아온다. 이 잡은 새 인덱스에 쓴 뒤 별칭을 바꾸는 방식이라
     * (newIndexName) 실패해도 현재 검색은 그대로다 — 임포트와 달리 재실행이 안전하다.
     */
    @PostMapping("/products/sync")
    public ResponseEntity<String> syncProducts() {
        Job productIndexingJob = allJobs.get("indexingJob");

        if (productIndexingJob == null) {
            return ResponseEntity.internalServerError().body("Product indexing job not found!");
        }

        if (!indexing.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("색인 작업이 이미 진행 중입니다.");
        }

        String newIndexName = "products-" + System.currentTimeMillis();
        JobParameters jobParameters = new JobParametersBuilder()
                .addLong("time", System.currentTimeMillis())
                .addString("newIndexName", newIndexName)
                .toJobParameters();

        // 재색인을 시작한 요청의 traceId 를 색인 스레드로 넘긴다(docs/51 §3).
        indexingExecutor.submit(com.project.common.logging.MdcTaskDecorator.wrap(() -> {
            try {
                jobLauncher.run(productIndexingJob, jobParameters);
                log.info("ES 재색인 완료: {}", newIndexName);
            } catch (Exception e) {
                // 잡 실패는 BATCH_JOB_EXECUTION 에 남는다. 여기서는 원인만 로그로 남긴다.
                log.error("ES 재색인 실패: {}", e.getMessage(), e);
            } finally {
                indexing.set(false);
            }
        }));

        return ResponseEntity.accepted().body("Batch synchronization job started: " + newIndexName);
    }

    // Test
    @GetMapping("/search/category")
    public ResponseEntity<?> searchProductsByCategory(@RequestParam("category") Long category, int pageNo,
            int pageSize) {

        PageRequest pageRequest = PageRequest.of(pageNo, pageSize);

        List<Product> products = searchService.findAllByCategory(category, pageRequest);

        return new ResponseEntity<>(products, HttpStatus.OK);

    }

}
