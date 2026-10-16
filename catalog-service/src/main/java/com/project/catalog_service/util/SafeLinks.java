package com.project.catalog_service.util;

import java.util.Locale;

/**
 * 화면이 {@code <a href>} 로 그리는 링크(배너 · 기획전)의 허용 모양 — http(s) 주소나 사이트 안 경로({@code /…}).
 *
 * <p>🐛 예전에는 길이만 봤다. 프론트는 배너 링크를 {@code <a href={banner.linkUrl}>} 로 그대로 그리고(CarouselContainer), React 18 은
 * {@code javascript:} 를 막지 않는다 — 관리자 계정 하나가 뚫리면 홈을 여는 모든 방문자에게 저장형 XSS 였다(공지 본문을 일반 텍스트로
 * 바꾼 것과 같은 위협, V18). {@code //evil.example} 같은 프로토콜 상대 주소와 제어 문자로 쪼갠 스킴({@code java\tscript:} — 브라우저는
 * 탭 · 줄바꿈을 지우고 읽는다)도 거절한다.
 *
 * <p>[JA] 画面が {@code <a href>} として描くリンクの許容形 — http(s) のアドレスかサイト内パス。以前は長さだけを見ており、
 * {@code javascript:} が保存型 XSS になった。
 */
public final class SafeLinks {

    public static final String MESSAGE = "링크는 http(s) 주소나 / 로 시작하는 사이트 안 경로여야 합니다.";

    private SafeLinks() {
    }

    /** 비었으면 null(링크 없음), 허용 모양이면 앞뒤 공백을 뗀 값, 아니면 {@link IllegalArgumentException}. */
    public static String check(String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        String s = link.strip();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f || Character.isWhitespace(c)) {
                throw new IllegalArgumentException(MESSAGE);
            }
        }
        if (s.startsWith("/") && !s.startsWith("//") && !s.startsWith("/\\")) {
            return s;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if ((lower.startsWith("https://") || lower.startsWith("http://")) && lower.length() > lower.indexOf("//") + 2) {
            return s;
        }
        throw new IllegalArgumentException(MESSAGE);
    }
}
