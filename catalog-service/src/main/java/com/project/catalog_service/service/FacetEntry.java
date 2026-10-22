package com.project.catalog_service.service;

import java.util.ArrayList;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 필터 메뉴 캐시 값 — 값과 계산한 시각. 오래되면(기본 10분) 그 값을 그대로 내주면서 뒤에서 다시 계산한다({@link SearchFilterFacets}).
 *
 * <p>캐시 직렬화기는 타입 이름을 남긴다 — 기본 생성자 · 세터가 있어야 읽히고, 목록은 {@link ArrayList} 여야 한다({@code List.of()} 의
 * 내부 클래스는 읽을 수 없다).
 *
 * <p>[JA] フィルターメニューのキャッシュ値 — 値と計算時刻。古ければそのまま返しつつ裏で計算し直す。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FacetEntry {
    private ArrayList<Object> values;
    private long computedAtMillis;
}
