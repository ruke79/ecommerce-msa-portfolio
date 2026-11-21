package com.project.catalog_service.batch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.batch.support.transaction.ResourcelessTransactionManager;

import com.project.catalog_service.service.ImageService.ImageUploadException;

/**
 * 상품 대량 등록의 내결함성(5단계 13) — 실제 Spring Batch 스텝(DB 없는 ResourcelessJobRepository)으로 돌린다.
 * 데이터 오류는 상한까지 건너뛰고, 이미지 업로드는 3번 다시 하되 건너뛰지 않는다.
 * [JA] 商品一括登録のフォールトトレランス — 実際のステップで回す。データの誤りは上限まで飛ばし、画像アップロードは 3 回やり直すが飛ばさない。
 */
@DisplayName("상품 등록 — 데이터 오류는 건너뛰고 · 이미지 업로드는 다시 하되 건너뛰지 않는다")
class ImportFaultToleranceTest {

    private final List<String> written = new ArrayList<>();

    private StepExecution run(List<String> items, ItemProcessor<String, String> processor, int skipLimit) throws Exception {
        JobRepository repo = new ResourcelessJobRepository();
        TaskletStep step = ImportFaultTolerance.apply(new StepBuilder("s", repo)
                .<String, String>chunk(10, new ResourcelessTransactionManager())
                .reader(new ListItemReader<>(items))
                .processor(processor)
                .writer(chunk -> written.addAll(chunk.getItems())), skipLimit)
                .build();
        JobExecution job = repo.createJobExecution("j", new JobParameters());
        StepExecution exec = job.createStepExecution("s");
        repo.add(exec);
        step.execute(exec);
        return exec;
    }

    @Test
    @DisplayName("🔴 모르는 id(IllegalArgumentException) 행은 건너뛰고 나머지는 저장 · 건너뛴 수가 남는다")
    void skipsDataErrors() throws Exception {
        StepExecution e = run(List.of("a", "bad", "b", "bad2", "c"), item -> {
            if (item.startsWith("bad")) {
                throw new IllegalArgumentException("Invalid product_id: " + item);
            }
            return item;
        }, 100);

        assertThat(e.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(written).containsExactly("a", "b", "c");
        assertThat(e.getSkipCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("건너뛴 수가 상한을 넘으면 스텝 실패")
    void failsOverSkipLimit() throws Exception {
        StepExecution e = run(List.of("bad1", "bad2", "bad3"), item -> {
            throw new IllegalArgumentException("Invalid: " + item);
        }, 2);
        assertThat(e.getStatus()).isEqualTo(BatchStatus.FAILED);
    }

    @Test
    @DisplayName("🔴 이미지 업로드가 잠깐 실패하면 다시 해서 저장한다(3번까지)")
    void retriesTransientUploadFailure() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        StepExecution e = run(List.of("a"), item -> {
            if (calls.incrementAndGet() < 3) {
                throw new ImageUploadException("이미지 업로드 실패: a.jpg", null);
            }
            return item;
        }, 100);

        assertThat(e.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(written).containsExactly("a");
        assertThat(e.getSkipCount()).isZero();
    }

    @Test
    @DisplayName("🔴 이미지 업로드가 계속 실패하면(자격 증명 폐기 등) 건너뛰지 않고 스텝 실패 — 이미지 없는 SKU 를 저장하지 않는다")
    void persistentUploadFailureFailsTheStep() throws Exception {
        StepExecution e = run(List.of("a", "b"), item -> {
            throw new ImageUploadException("이미지 업로드 실패: " + item, null);
        }, 100);

        assertThat(e.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(e.getSkipCount()).isZero();
        assertThat(written).isEmpty();
    }
}
