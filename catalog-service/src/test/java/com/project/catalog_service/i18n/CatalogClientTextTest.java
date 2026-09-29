package com.project.catalog_service.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.project.common.i18n.ClientText;

/** catalog-service 의 서버 문구가 일본어 카탈로그(i18n/client-text.ja.txt)로 바뀌는지 (docs/60 §4). */
@DisplayName("catalog-service 서버 문구 일본어 번역")
class CatalogClientTextTest {

    @Test
    @DisplayName("고정 문구는 그대로 찾는다")
    void exactMessage() {
        assertThat(ClientText.localize("기획전을 찾을 수 없습니다.", Locale.JAPANESE))
                .isEqualTo("イベントが見つかりません。");
        assertThat(ClientText.localize("재고를 확인하지 못했습니다. 잠시 뒤 다시 시도해 주세요.", Locale.JAPANESE))
                .isEqualTo("在庫を確認できませんでした。しばらくしてから再度お試しください。");
    }

    @Test
    @DisplayName("바뀌는 부분이 있는 문구 — 값은 그대로 옮긴다")
    void patternMessage() {
        assertThat(ClientText.localize("한 기획전에는 상품을 200개까지 담을 수 있습니다.", Locale.JAPANESE))
                .isEqualTo("1つのイベントに登録できる商品は200点までです。");
        assertThat(ClientText.localize("존재하지 않는 서브카테고리가 있습니다: 요청 3개 중 2개만 찾음", Locale.JAPANESE))
                .isEqualTo("存在しないサブカテゴリーが含まれています：指定された3件のうち2件のみ見つかりました。");
    }

    @Test
    @DisplayName("예외 생성자에서 만든 문구도 — 바뀌는 부분을 다시 찾는다")
    void helperBuiltMessage() {
        assertThat(ClientText.localize("이미지 저장소에 쓸 수 없습니다: 원인 미상", Locale.JAPANESE))
                .isEqualTo("画像ストレージに書き込めません：原因不明");
    }

    @Test
    @DisplayName("한국어 요청은 그대로")
    void koreanUnchanged() {
        assertThat(ClientText.localize("기획전을 찾을 수 없습니다.", Locale.KOREAN)).isEqualTo("기획전을 찾을 수 없습니다.");
    }
}
