package com.project.catalog_service.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Service;

import com.project.catalog_service.mapper.ProductDocumentMapper;
import com.project.catalog_service.model.Product;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 상품 변경의 실시간 ES 반영.
 *
 * <p>배치 색인(POST /api/products/sync)은 새 인덱스를 만들어 별칭을 갈아끼우는 전체
 * 재색인이다 — 등록 하나 반영하자고 부를 물건이 아니다. 그래서 지금까지 ES 모드에서는
 * **등록한 상품이 다음 수동 sync 까지 검색에 안 나왔다.** 이 서비스가 그 간극을 메운다:
 * 단건을 별칭("products")에 바로 쓴다.
 *
 * <p>설계 결정 둘:
 * <ul>
 *   <li><b>색인 실패는 상품 저장을 실패시키지 않는다.</b> 검색 반영이 늦는 것(다음
 *       sync 로 복구된다)과 상품 등록이 실패하는 것은 무게가 다르다. 실패는 경고
 *       로그로 남긴다 — ES 가 꺼져 있는 배포에서도 등록은 되어야 한다
 *   <li><b>search.engine=es 일 때만 동작한다.</b> 기본(postgres LIKE)에서는 검색이
 *       DB 를 직접 보므로 색인 자체가 무의미하고, ES 컨테이너가 없는 배포에서 매
 *       등록마다 연결 실패 경고가 쌓이는 것을 막는다
 * </ul>
 *
 * <p>품절(SOLD_OUT)은 색인을 유지한다 — 검색에 보이되 담기지 않는 것이 목록과 같은
 * 동작이다. 삭제(DELETED)만 색인에서 뺀다.
 *
 * <h3>🔴 어디에 쓰는가 — 이 클래스가 하는 일의 전부가 걸려 있던 지점</h3>
 *
 * <p>예전에는 {@code ProductDocumentRepository} 로 저장했다. 그 저장소는
 * {@code ProductDocument} 의 {@code @Document(indexName = "products")} 를 따르므로
 * <b>{@code products} 인덱스</b>에 썼다. 그런데 검색({@link EsProductSearchService})은
 * 배치가 롤오버하는 <b>별칭 {@code product-alias}</b>(실체는
 * {@code products-<타임스탬프>-products})를 읽는다.
 *
 * <p>즉 <b>쓰는 곳과 읽는 곳이 다른 인덱스였다.</b> 새로 등록한 상품은 ES 모드에서
 * 검색에 나오지 않았고 — 이 클래스가 메우려던 바로 그 간극이다 — 게다가 아무도 읽지 않는
 * {@code products} 인덱스가 동적 매핑으로 조용히 생겨 자랐다. 실패 로그도 남지 않는다.
 * 저장이 <b>성공</b>했기 때문이다.
 *
 * <p>이제 별칭에 직접 쓴다. 별칭이 아직 없으면(전체 재색인을 한 번도 돌리지 않은 배포)
 * <b>쓰지 않고 경고만 남긴다</b> — 그 이름으로 쓰면 ES 가 같은 이름의 <i>인덱스</i>를
 * 만들어 버리고, 그러면 나중에 배치의 별칭 롤오버가 영구히 실패한다(같은 이름의 인덱스와
 * 별칭은 공존할 수 없다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductIndexService {

    private final ElasticsearchOperations elasticsearchOperations;

    @Value("${search.engine:postgres}")
    private String searchEngine;

    /** 검색이 읽는 것과 <b>같은</b> 별칭이어야 한다 — EsProductSearchService 와 기본값을 맞춘다. */
    @Value("${elasticsearch.alias.name.product:product-alias}")
    private String productAliasName;

    public void index(Product product) {
        IndexCoordinates target = writableTarget();
        if (target == null) {
            return;
        }
        try {
            elasticsearchOperations.save(ProductDocumentMapper.toDocument(product), target);
        } catch (Exception e) {
            log.warn("ES 단건 색인 실패 — 검색 반영이 다음 sync 까지 늦습니다. productId={}, 원인={}",
                    product.getProductId(), e.getMessage());
        }
    }

    public void remove(Long productId) {
        IndexCoordinates target = writableTarget();
        if (target == null) {
            return;
        }
        try {
            elasticsearchOperations.delete(String.valueOf(productId), target);
        } catch (Exception e) {
            log.warn("ES 색인 삭제 실패 — 삭제된 상품이 검색에 남을 수 있습니다(다음 sync 에서 정리). "
                    + "productId={}, 원인={}", productId, e.getMessage());
        }
    }

    /** 쓸 수 있는 대상. ES 모드가 아니거나 별칭이 아직 없으면 null. */
    private IndexCoordinates writableTarget() {
        if (!"es".equalsIgnoreCase(searchEngine)) {
            return null;
        }
        IndexCoordinates target = IndexCoordinates.of(productAliasName);
        try {
            if (!elasticsearchOperations.indexOps(target).exists()) {
                log.warn("ES 별칭 '{}' 이 아직 없어 단건 색인을 건너뜁니다. "
                        + "POST /api/products/sync 로 전체 재색인을 한 번 돌려 주세요.", productAliasName);
                return null;
            }
        } catch (Exception e) {
            log.warn("ES 별칭 확인 실패 — 단건 색인을 건너뜁니다. 별칭={}, 원인={}",
                    productAliasName, e.getMessage());
            return null;
        }
        return target;
    }
}
