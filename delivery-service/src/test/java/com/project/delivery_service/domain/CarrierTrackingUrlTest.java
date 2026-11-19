package com.project.delivery_service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 택배 조회 링크의 송장번호 인코딩(docs/57 §22). 🐛 예전에는 글자 그대로 넣어 {@code &} · {@code #} 가 링크를 바꿨다.
 * [JA] 追跡リンクの送り状番号エンコード。以前はそのまま入れ & · # がリンクを変えた。
 */
class CarrierTrackingUrlTest {

    private final Carrier post = new Carrier("KOREA_POST", "우체국택배", "Korea Post",
            "https://service.epost.go.kr/trace.RetrieveDomRigiTraceList.comm?sid1={trackingNumber}", true);

    @Test
    @DisplayName("보통 송장번호(숫자 · 영문 · 하이픈)는 그대로")
    void plainNumbersStay() {
        assertThat(post.trackingUrlFor("6070-1234-5678"))
                .isEqualTo("https://service.epost.go.kr/trace.RetrieveDomRigiTraceList.comm?sid1=6070-1234-5678");
    }

    @Test
    @DisplayName("🔴 & · # · / · 공백 · 한글은 퍼센트 인코딩 — 다른 매개변수 · 조각을 끼우지 못한다")
    void specialCharactersAreEncoded() {
        assertThat(post.trackingUrlFor("123&sid2=x#frag/ 가"))
                .isEqualTo("https://service.epost.go.kr/trace.RetrieveDomRigiTraceList.comm?sid1=123%26sid2%3Dx%23frag%2F%20%EA%B0%80");
    }

    @Test
    @DisplayName("템플릿 · 송장번호가 없으면 null")
    void nullWhenMissing() {
        assertThat(new Carrier("SIMULATED", "테스트배송", "Simulated", null, true).trackingUrlFor("1")).isNull();
        assertThat(post.trackingUrlFor(null)).isNull();
    }
}
