package com.project.common.logging;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 요청의 사용자 id 를 로그 문맥(MDC {@code userId})에 넣는다.
 *
 * <p>traceId·spanId 는 Micrometer Tracing 이 이미 MDC 에 넣는다 — 요청 하나를 서비스를 넘어
 * 따라갈 수 있다. 빠져 있던 것은 <b>"누구의 요청인가"</b> 였다. "이 사용자가 결제가 안 된다고 한다"
 * 는 문의가 오면 지금까지는 이메일로 로그를 grep 하는 수밖에 없었고, 그 이메일은 2단계부터
 * 가려진다. 이제 {@code userId} 로 찾는다(JSON 로그는 필드, 텍스트 로그는 대괄호 안 세 번째 값).
 *
 * <p><b>값은 게이트웨이가 넣은 {@code User-Id} 헤더다.</b> 게이트웨이는 클라이언트가 보낸 신원 헤더를
 * 지우고 검증한 토큰에서 다시 넣는다({@code JwtAuthorizationGatewayFilterFactory}). 게이트웨이를
 * 거치지 않은 요청이 헤더를 꾸며 넣을 수는 있지만, 여기서는 <b>로그의 표시</b>에만 쓰고 어떤 판단에도
 * 쓰지 않는다. 그래도 로그 인젝션(줄바꿈으로 가짜 줄 만들기)은 막는다 — 글자를 거르고 길이를 자른다.
 *
 * <p>끝나면 {@code userId} 만 지운다. {@code MDC.clear()} 를 하면 추적이 넣은 traceId 까지 지워
 * 같은 스레드의 뒤따르는 로그가 추적을 잃는다.
 *
 * <p>[JA] リクエストのユーザー id をログコンテキスト(MDC {@code userId})に入れる。
 *
 * <p>traceId・spanId は Micrometer Tracing がすでに MDC に入れている — 一つのリクエストをサービスをまたいで
 * 追跡できる。欠けていたのは<b>「誰のリクエストか」</b>だった。「このユーザーが決済できないと言っている」
 * という問い合わせが来ると、これまではメールアドレスでログを grep するしかなく、しかもそのメールアドレスは
 * 2段階目からマスキングされる。今後は {@code userId} で探す(JSON ログではフィールド、テキストログでは角括弧内の
 * 三番目の値)。
 *
 * <p><b>値はゲートウェイが入れた {@code User-Id} ヘッダーである。</b> ゲートウェイはクライアントが送った身元ヘッダーを
 * 消し、検証したトークンから入れ直す({@code JwtAuthorizationGatewayFilterFactory})。ゲートウェイを
 * 経由しないリクエストがヘッダーを偽装して入れることはできるが、ここでは<b>ログの表示</b>にだけ使い、いかなる判断にも
 * 使わない。それでもログインジェクション(改行で偽の行を作ること)は防ぐ — 文字をフィルタリングし、長さを切り詰める。
 *
 * <p>終わったら {@code userId} だけを消す。{@code MDC.clear()} をするとトレースが入れた traceId まで消え、
 * 同じスレッドで後続するログがトレースを失う。
 */
public class UserMdcFilter extends OncePerRequestFilter {

    public static final String HEADER = "User-Id";
    public static final String MDC_KEY = "userId";

    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userId = sanitize(request.getHeader(HEADER));
        if (userId == null) {
            chain.doFilter(request, response);
            return;
        }
        MDC.put(MDC_KEY, userId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** 영숫자와 {@code . _ : -} 만 남기고 64자로 자른다. 남는 것이 없으면 null. */
    // [JA] 英数字と {@code . _ : -} だけを残し、64文字に切り詰める。何も残らなければ null。
    static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[^A-Za-z0-9._:-]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }
}
