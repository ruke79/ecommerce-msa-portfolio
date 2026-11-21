package com.project.catalog_service.batch.config;

import org.springframework.batch.core.SkipListener;
import org.springframework.batch.core.step.builder.FaultTolerantStepBuilder;
import org.springframework.batch.core.step.builder.SimpleStepBuilder;
import org.springframework.batch.item.file.FlatFileParseException;

import com.project.catalog_service.service.ImageService.ImageUploadException;

import lombok.extern.slf4j.Slf4j;

/**
 * 상품 대량 등록 스텝의 내결함성(5단계 13) — 모든 스텝이 같은 규칙을 쓴다.
 *
 * <ul>
 *   <li><b>건너뛴다</b>: 데이터 오류 — 모르는 id({@link IllegalArgumentException}, 예: 없는 product_id · color_id) · CSV 한 줄을 읽지
 *       못함({@link FlatFileParseException}). 상한(skipLimit)까지 — 넘으면 스텝 실패. 건너뛴 행은 WARN 한 줄씩 남고, 수는 등록 상태의
 *       스텝 결과(skipped)에 보인다. 앞 스텝에서 건너뛴 상품을 가리키는 뒤 스텝의 행도 같은 이유로 건너뛴다.</li>
 *   <li><b>다시 한다</b>: 이미지 업로드({@link ImageUploadException}) 3번. <b>건너뛰지 않는다</b> — 자격 증명이 폐기되면 모든 업로드가
 *       실패하는데, 그것을 건너뛰면 이미지 없는 SKU 가 '성공' 으로 저장된다(ProductSkuItemProcessor 의 결정: "절반만 들어간 데이터"
 *       보다 "실패했다고 말하는 잡"). 세 번 다 실패하면 스텝 실패.</li>
 * </ul>
 * 🐛 예전에는 내결함성이 전혀 없어 잘못된 행 하나가 등록 전체를 그 자리에서 멈췄다(앞 청크는 이미 저장돼 반쯤 등록된 상태).
 *
 * <p>[JA] 商品一括登録ステップのフォールトトレランス。データの誤り(未知の id · 読めない CSV 行)は上限まで飛ばしてログ · 件数に残す。
 *      画像アップロードは 3 回やり直し、飛ばさない(資格情報の失効で画像のない SKU が「成功」として保存されないように)。
 */
@Slf4j
public final class ImportFaultTolerance {

    public static final int RETRY_LIMIT = 3;

    private ImportFaultTolerance() {
    }

    public static <I, O> FaultTolerantStepBuilder<I, O> apply(SimpleStepBuilder<I, O> step, int skipLimit) {
        return step.faultTolerant()
                .skipLimit(skipLimit)
                .skip(IllegalArgumentException.class)
                .skip(FlatFileParseException.class)
                .noSkip(ImageUploadException.class)
                .retryLimit(RETRY_LIMIT)
                .retry(ImageUploadException.class)
                .listener(new SkipLog<I, O>());
    }

    /** 건너뛴 행을 WARN 한 줄로 — 이유만(행 전체는 남기지 않는다). [JA] 飛ばした行を WARN 一行で — 理由だけ。 */
    static final class SkipLog<I, O> implements SkipListener<I, O> {
        @Override
        public void onSkipInRead(Throwable t) {
            log.warn("상품 등록 — 읽지 못한 행을 건너뛴다: {}", t.getMessage());
        }

        @Override
        public void onSkipInProcess(I item, Throwable t) {
            log.warn("상품 등록 — 처리하지 못한 행을 건너뛴다: {}", t.getMessage());
        }

        @Override
        public void onSkipInWrite(O item, Throwable t) {
            log.warn("상품 등록 — 쓰지 못한 행을 건너뛴다: {}", t.getMessage());
        }
    }
}
