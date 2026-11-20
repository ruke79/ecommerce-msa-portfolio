package com.project.catalog_service.repository.jpa.data;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.project.catalog_service.model.ProductDetails;

@Repository
public interface ProductDetailsRepository extends JpaRepository<ProductDetails, Long> {

    /**
     * 필터 메뉴의 상세(스타일 · 소재 …) — 한 카테고리의 (이름, 값) 쌍, 중복 없이. 행은 {@code [name, value]}.
     *
     * <p>🐛 2026-10-19 데이터 규모 측정(docs/83 §5): 예전에는 카테고리의 상품 id 를 전부 읽어
     * {@code findDistinctAllByProductProductIdIn(ids)} 로 상세 <b>엔티티</b>를 상품마다 읽었다 — 화면은 이름 · 값만 쓰고
     * 중복을 지운다(browse.jsx {@code removeDublicates}). 상품이 65,535 개를 넘는 카테고리에서는 바인드 변수 한도로 실패했다.
     * [JA] フィルターメニューの詳細(スタイル · 素材 …)— 一つのカテゴリの (名前, 値) の組を重複なしに。行は {@code [name, value]}。
     * 🐛 2026-10-19 データ規模計測(docs/83 §5): 以前はカテゴリの商品 id を全部読み、{@code findDistinctAllByProductProductIdIn(ids)} で
     * 詳細<b>エンティティ</b>を商品ごとに読んでいた — 画面は名前 · 値だけを使い、重複を消す(browse.jsx {@code removeDublicates})。
     * 商品が 65,535 個を超えるカテゴリではバインド変数の上限で失敗した。
     */
    @Query(value = "select distinct d.name, d.value from product.product_details d " +
            "inner join product.product p on p.product_id = d.product_id " +
            "where p.category_id = :categoryId", nativeQuery = true)
    List<Object[]> findDistinctNameValuesByCategoryId(@Param("categoryId") Long categoryId);

    /** 카테고리 없는 검색의 상세 메뉴 — 전체의 (이름, 값) 쌍, 중복 없이(예전 {@code select distinct d} 는 엔티티라 행마다였다). */
    // [JA] カテゴリなし検索の詳細メニュー — 全体の(名前, 値)の組を重複なしで(以前の select distinct d はエンティティなので行ごとだった)。
    @Query(value = "select distinct d.name, d.value from product.product_details d", nativeQuery = true)
    List<Object[]> findDistinctNameValues();
}
