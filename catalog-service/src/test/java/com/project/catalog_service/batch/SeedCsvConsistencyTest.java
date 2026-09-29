package com.project.catalog_service.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 시드 CSV 의 상품 ↔ 하위 카테고리 연결은 <b>같은 카테고리 안</b>이어야 한다.
 *
 * <p>🐛 연결 499 건 중 404 건(81%)이 다른 카테고리였다 — 검색 화면에서 "men's clothing" 을 누르면 침대 프레임 · 소설 · 쿠키가
 * 나왔다. 운영 데이터는 V16 이 고쳤고, 이 테스트는 CSV 가 다시 그렇게 되지 않게 막는다.
 */
@DisplayName("시드 CSV — 상품의 하위 카테고리는 상품의 카테고리에 속한다")
class SeedCsvConsistencyTest {

    private static List<String[]> rows(String name) throws IOException {
        try (InputStream in = SeedCsvConsistencyTest.class.getResourceAsStream("/csv/" + name)) {
            assertThat(in).as(name).isNotNull();
            List<String[]> out = new ArrayList<>();
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().skip(1).toList();
            for (String line : lines) {
                if (!line.isBlank()) {
                    out.add(parse(line));
                }
            }
            return out;
        }
    }

    /** 따옴표 안의 쉼표를 지키는 최소 CSV 파서(설명 칸에 쉼표가 있다). */
    private static String[] parse(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                cells.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        cells.add(cur.toString());
        return cells.toArray(String[]::new);
    }

    @Test
    @DisplayName("🔴 모든 연결의 하위 카테고리가 상품과 같은 카테고리다")
    void linksStayInsideProductCategory() throws IOException {
        Map<String, String> productCategory = new HashMap<>();
        Map<String, String> productName = new HashMap<>();
        for (String[] r : rows("products.csv")) {       // product_id,name,description,brand,slug,category_id,…
            productCategory.put(r[0], r[5]);
            productName.put(r[0], r[1]);
        }
        Map<String, String> subCategory = new HashMap<>();
        Map<String, String> subName = new HashMap<>();
        for (String[] r : rows("subcategories.csv")) {  // subcategory_id,subcategory_name,slug,category_id,…
            subCategory.put(r[0], r[3]);
            subName.put(r[0], r[1]);
        }

        List<String[]> links = rows("product_subcategories.csv");
        List<String> wrong = new ArrayList<>();
        for (String[] l : links) {                      // id,product_id,subcategory_id
            String pc = productCategory.get(l[1]);
            String sc = subCategory.get(l[2]);
            if (pc == null || sc == null || !pc.equals(sc)) {
                wrong.add(productName.get(l[1]) + " → " + subName.get(l[2]));
            }
        }
        assertThat(links).isNotEmpty();
        assertThat(wrong).as("다른 카테고리의 하위 카테고리에 이어진 상품").isEmpty();
    }
}
