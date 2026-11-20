package com.project.common.dto.catalog.response;

/**
 * 상품(SKU) 생성 결과 — POST /api/product 의 응답.
 *
 * <p>예전에는 ProductSku **엔티티를 통째로** 직렬화해 돌려줬다. 문제가 둘이었다:
 * 지연 로딩 프록시가 직렬화 시점에 터질 수 있고, 정작 화면이 다음 단계(같은 상품에
 * 색상 변형 이어서 등록)에 필요한 {@code productId} 는 @JsonBackReference 에 잘려
 * **응답에 없었다.** 필요한 세 값만 명시적으로 준다.
 *
 * <p>[JA] 商品(SKU)作成結果 — POST /api/product の応答。
 * 以前は ProductSku **エンティティを丸ごと**シリアライズして返していた。問題は二つあった: 遅延ロードのプロキシがシリアライズ時点で破裂しうること、
 * そして肝心の画面が次の段階(同じ商品に色バリエーションを続けて登録)に必要な {@code productId} が @JsonBackReference で切られ**応答になかった**こと。必要な三つの値だけを明示的に返す。
 */
public record ProductCreatedResponse(String sku, Long productId, String slug) {
}
