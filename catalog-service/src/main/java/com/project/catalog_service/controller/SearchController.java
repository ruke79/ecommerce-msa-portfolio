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

        SearchResultDto dto = searchService.searchProducts(params);

        return new ResponseEntity<>(withStock(dto), HttpStatus.OK);

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
