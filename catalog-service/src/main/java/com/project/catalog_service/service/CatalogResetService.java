package com.project.catalog_service.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * CSV 일괄 임포트를 다시 돌리기 위해 카탈로그를 비운다.
 *
 * <h2>왜 필요한가 — 임포트가 멱등하지 않다</h2>
 *
 * <p>프로세서들은 조회 없이 새 엔티티를 만들어 넣고({@code JpaItemWriter} 도 persist 로
 * 덮어 두었다), {@code ProductImportService} 는 실행마다 {@code time} 파라미터를 넣어 <b>새
 * JobInstance</b> 를 만든다. 즉 스프링 배치의 재시작이 동작하지 않고 언제나 1단계부터 다시
 * 돈다. 실패한 임포트를 다시 누르면 이미 들어간 것 위에 또 넣는다.
 *
 * <p>실제로 그렇게 됐다. 이미지 업로드(S3 권한)에서 죽은 뒤 다시 눌렀더니 카테고리 5→10,
 * 하위 카테고리 15→30 이 되고 상품은 {@code uq_product_slug} 에 걸려 멈췄다. 유니크 제약이
 * 없었다면 상품 500개짜리 카탈로그가 조용히 만들어졌을 것이다. 운영자는 psql 로 직접
 * TRUNCATE 해야 했다 — 그 일을 없애는 것이 이 클래스다.
 *
 * <h2>왜 DELETE 가 아니라 TRUNCATE ... RESTART IDENTITY 인가</h2>
 *
 * <p><b>임포트는 식별자가 1부터 시작한다는 것에 의존한다.</b> {@code CategoryItemProcessor}
 * 는 id 를 CSV 에서 가져오지 않고 DB 가 생성하게 두는데, {@code ProductItemProcessor} 는
 * {@code categoryRepository.findById(item.getCategory_id())} 로 <b>CSV 에 적힌 id</b> 를 찾는다.
 * 두 값이 맞아떨어지는 것은 시퀀스가 1 부터 시작할 때뿐이다.
 *
 * <p>그래서 행만 지우고 시퀀스를 남기면 다음 임포트가
 * {@code IllegalArgumentException: Invalid category_id: 1} 로 죽는다. 같은 이유로, 임포트
 * 전에 {@code /admin/category} 로 카테고리를 하나라도 만들어 두면 id 가 밀려 실패한다
 * (그 의존 자체가 결함이다 — docs/42 에 적어 두었다).
 *
 * <p>비우는 범위는 <b>CSV 가 만드는 것</b>뿐이다. {@code product.processed_event}(사가
 * 멱등성 원장)와 {@code content.*}(배너·공지·홈 섹션)는 건드리지 않는다 — 임포트가 만든
 * 것이 아니고, 홈 섹션을 지우면 첫 화면 구성이 통째로 사라진다.
 */
@Slf4j
@Service
public class CatalogResetService {

    /**
     * 비울 테이블. <b>하드코딩이다</b> — 이름이 밖에서 들어오면 그대로 SQL 이 된다.
     *
     * <p>순서는 의미가 없다(CASCADE 가 처리한다). 읽는 사람이 자식→부모로 확인하기 쉽도록
     * 그 순서로 적었다.
     */
    private static final List<String> CATALOG_TABLES = List.of(
            "product.product_sku",
            "product.product_color",
            "product.product_details",
            "product.product_qa",
            "product.product_size",
            "product.product_subcategory",
            "product.product",
            "product.subcategory",
            "product.category");

    @PersistenceContext
    private EntityManager entityManager;

    /** 임포트가 만든 것이 하나라도 있는가. 재실행 전에 이걸 먼저 묻는다. */
    @Transactional(readOnly = true)
    public long countCatalogRows() {
        Number n = (Number) entityManager
                .createNativeQuery("select (select count(*) from product.category)"
                        + " + (select count(*) from product.subcategory)"
                        + " + (select count(*) from product.product)")
                .getSingleResult();
        return n == null ? 0L : n.longValue();
    }

    /**
     * 카탈로그를 비우고 식별자 시퀀스를 1 로 되돌린다.
     *
     * <p>되돌릴 수 없는 동작이라 {@code warn} 으로 남긴다. 호출자는 관리자 인증을 거치고
     * {@code reset=true} 를 명시적으로 넘긴 경우뿐이다.
     */
    @Transactional
    public void reset() {
        long before = countCatalogRows();
        entityManager.createNativeQuery(
                "TRUNCATE " + String.join(", ", CATALOG_TABLES) + " RESTART IDENTITY CASCADE")
                .executeUpdate();
        log.warn("카탈로그를 비웠습니다 — 임포트 재실행 준비 (이전 카테고리+하위카테고리+상품 {}행)", before);
    }
}
