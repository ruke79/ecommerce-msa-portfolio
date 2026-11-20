package com.project.common.filter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.project.common.context.TokenContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

//  JwtTokenFilter를 common 모듈에 추가하고 다른 서비스들이 이를 사용하는 것은
//  Spring Security를 사용하는 user-service의 필터와 충돌하는 것이 아니라,
//   오히려 마이크로 서비스 아키텍처에서 인증 로직을 분산 처리하는 표준적인 방식
// [JA] JwtTokenFilter を common モジュールに追加し他サービスがそれを使うのは、Spring Security を使う user-service のフィルターと衝突するものではなく、
//      むしろマイクロサービスアーキテクチャで認証ロジックを分散処理する標準的な方式である
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // 필터 체인에서 가장 먼저 실행되도록 설정 ／ [JA] フィルターチェーンで最初に実行されるように設定
public class JwtTokenFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        try {
            String token = request.getHeader("Authorization");
            if (token != null && token.startsWith("Bearer ")) {
                TokenContext.setToken(token);
            }
            filterChain.doFilter(request, response);
        } finally {
            // 요청 처리가 끝난 후 반드시 토큰을 제거하여 메모리 누수 방지
            // [JA] リクエスト処理の終了後に必ずトークンを除去してメモリリークを防ぐ
            TokenContext.clear();
        }
    }
}