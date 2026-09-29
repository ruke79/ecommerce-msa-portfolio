package com.project.gatewayserver.i18n;

import java.util.List;
import java.util.Locale;

import org.springframework.web.server.ServerWebExchange;

/**
 * 게이트웨이가 직접 돌려주는 문구를 요청 언어로 (docs/60 §4).
 *
 * <p>서비스들은 common 의 ClientText(서블릿)로 바꾸지만 게이트웨이는 WebFlux 이고 문구가 다섯 개뿐이라 여기서 둘 다 들고
 * 고른다. 화면은 모든 요청에 Accept-Language 를 싣는다.
 */
public final class GatewayText {

    private GatewayText() {
    }

    public static boolean japanese(ServerWebExchange exchange) {
        List<Locale.LanguageRange> ranges;
        try {
            ranges = exchange.getRequest().getHeaders().getAcceptLanguage();
        } catch (IllegalArgumentException e) {
            return false;   // 형식이 틀린 헤더 — 기본(한국어)
        }
        return !ranges.isEmpty() && ranges.get(0).getRange().toLowerCase(Locale.ROOT).startsWith("ja");
    }

    public static String pick(ServerWebExchange exchange, String ko, String ja) {
        return japanese(exchange) ? ja : ko;
    }
}
