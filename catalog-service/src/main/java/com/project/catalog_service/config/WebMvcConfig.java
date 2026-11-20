package com.project.catalog_service.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

/**
 * AdminWriteInterceptor 를 모든 API 경로에 건다.
 *
 * <p>경로를 여기서 좁히지 않는 이유: 인터셉터가 스스로 "쓰기 메서드만" 거르므로,
 * 여기서 또 경로 목록을 관리하면 새 컨트롤러가 추가될 때 **두 곳**을 같이 고쳐야 하고,
 * 잊으면 그 엔드포인트만 조용히 무방비가 된다 — 정확히 이번에 겪은 형태의 구멍이다.
 * 기본을 "쓰기는 전부 관리자"로 두고, 예외(별점)는 인터셉터 안에 명시한다.
 *
 * <p>[JA] AdminWriteInterceptor をすべての API パスにかける。
 *      パスをここで絞らない理由: インターセプタ自身が「書き込みメソッドだけ」を選別するので、
 *      ここでもパス一覧を管理すると新しいコントローラが追加されたときに **2 か所** を一緒に直す必要があり、
 *      忘れるとそのエンドポイントだけが黙って無防備になる — まさに今回経験した形の穴である。
 *      既定を「書き込みはすべて管理者」とし、例外(星評価)はインターセプタの中に明示する。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminWriteInterceptor adminWriteInterceptor;
    private final EdgeCacheHeaders edgeCacheHeaders;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 앞단 캐시 기본값(no-store) — 관리자 검사보다 먼저, 401 · 403 응답에도 붙게(docs/91 §2-9)
        // [JA] 前段キャッシュの既定値(no-store) — 管理者検査より先に、401 · 403 応答にも付くように(docs/91 §2-9)
        registry.addInterceptor(edgeCacheHeaders)
                .addPathPatterns("/api/**");
        registry.addInterceptor(adminWriteInterceptor)
                .addPathPatterns("/api/**")
                // actuator 는 API 가 아니고, 프로브가 GET 만 쓰지만 명시적으로 뺀다.
                // [JA] actuator は API ではなく、プローブは GET しか使わないが明示的に外す。
                .excludePathPatterns("/actuator/**");
    }
}
