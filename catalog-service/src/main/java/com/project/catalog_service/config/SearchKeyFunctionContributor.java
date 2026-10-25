package com.project.catalog_service.config;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * HQL 함수 {@code search_key(x)} → SQL {@code product.search_key(x)}(catalog V23 — NFKC + 소문자), 결과는 문자열.
 *
 * <p>JPA 의 {@code function('…')} 은 결과 형을 몰라 {@code like} 의 피연산자가 되지 못하고, {@code cast(… as String)} 은
 * {@code varchar} 로 감싸 식 인덱스(idx_product_name_search_key_trgm)와 식이 어긋난다. 그래서 이름과 결과 형을 등록한다.
 * {@code META-INF/services/org.hibernate.boot.model.FunctionContributor} 로 읽힌다.
 *
 * <p>[JA] HQL 関数 search_key(x) → SQL product.search_key(x)、結果は文字列。function('…') は型が分からず like に使えず、
 * cast は式インデックスとずれるので、名前と結果型を登録する。
 */
public class SearchKeyFunctionContributor implements FunctionContributor {

    @Override
    public void contributeFunctions(FunctionContributions functions) {
        functions.getFunctionRegistry().registerPattern("search_key", "product.search_key(?1)",
                functions.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.STRING));
    }
}
