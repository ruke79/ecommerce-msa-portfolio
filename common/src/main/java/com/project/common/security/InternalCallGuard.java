package com.project.common.security;

/**
 * "이 요청이 게이트웨이를 거쳐 온 것인가" 를 판정한다.
 *
 * <h3>왜 필요한가</h3>
 *
 * <p>서비스 간 내부 호출(Feign)은 게이트웨이를 거치지 않는다. 그래서 내부 전용
 * 엔드포인트는 <b>"게이트웨이가 붙인 신원 헤더가 없으면 내부 호출"</b> 로 판정해 왔다.
 * 외부에서 온 요청에는 헤더가 있고, 그때는 관리자만 허용한다.
 *
 * <h3>🔴 헤더 하나만 보면 생기는 구멍</h3>
 *
 * <p>여러 곳이 {@code User-Role} <b>하나만</b> 봤다. 그런데 게이트웨이는 토큰에서 뽑은
 * 값이 있을 때만 그 헤더를 붙인다:
 *
 * <pre>
 *   String role = extractRoleFromJwt(jwt);
 *   ...
 *   if (role != null) { mutated.header("User-Role", role); }
 * </pre>
 *
 * <p>즉 {@code role} 클레임이 없는 토큰으로 들어오면 <b>헤더가 붙지 않고</b>, 받는 쪽은
 * 그것을 "내부 호출"로 읽어 통과시킨다. 지금 발급되는 토큰에는 role 이 항상 들어가므로
 * (JwtUtils 가 {@code user.getRole()} 에서 채우고 그 관계는 NOT NULL 이다) 실제로 뚫린
 * 적은 없다. 하지만 <b>구조가 그것을 보장하지 않는다</b> — 클레임 이름이 바뀌거나,
 * 다른 경로로 발급된 토큰이 생기거나, 파싱이 실패하면 그날 조용히 열린다.
 *
 * <p>그래서 <b>세 헤더 중 하나라도</b> 있으면 외부 요청으로 본다. {@code User-Email} 은
 * JWT subject 라 언제나 있고, {@code User-Id} 는 없으면 게이트웨이가 요청 자체를 끊는다.
 * 하나가 빠져도 나머지가 남는다.
 *
 * <p>🔒 <b>서비스 간 mTLS</b>(docs/57 §16)가 켜져 있으면(enforce) "헤더 없음" 만으로는 내부가 아니다 —
 * {@link InternalCallerFilter} 가 내부 CA 의 클라이언트 인증서를 내지 않은 헤더 없는 요청에 {@code User-Role: ROLE_ANONYMOUS}
 * 를 붙여, 이 판정이 그것을 "외부·관리자 아님" 으로 읽는다. 게이트웨이의 공개 라우트와 클러스터 안의 다른 파드가 여기 걸린다.
 *
 * <p>⚠️ 이 판정은 <b>게이트웨이가 클라이언트의 신원 헤더를 지운다</b>는 전제 위에 있다.
 * {@code JwtAuthorizationGatewayFilterFactory} 와 {@code IdentityHeaderScrubFilter} 가
 * 그 일을 한다. 둘 중 하나라도 무력화되면 이 판정은 뒤집힌다 — 클라이언트가 헤더를 직접
 * 붙여 "외부"로 보이게 만드는 것은 권한을 <b>낮추는</b> 방향이라 무해하지만, 헤더를
 * 지우는 쪽이 깨지면 {@code User-Role: ROLE_ADMIN} 한 줄로 관리자가 된다.
 *
 * <p>[JA] 「このリクエストはゲートウェイを経由して来たものか」を判定する。
 *
 * <h3>なぜ必要か</h3>
 *
 * <p>サービス間の内部呼び出し(Feign)はゲートウェイを経由しない。そのため内部専用
 * エンドポイントは <b>「ゲートウェイが付けた身元ヘッダーがなければ内部呼び出し」</b> と判定してきた。
 * 外部から来たリクエストにはヘッダーがあり、その場合は管理者だけを許可する。
 *
 * <h3>🔴 ヘッダーを一つだけ見ると生じる穴</h3>
 *
 * <p>複数の箇所が {@code User-Role} <b>一つだけ</b>を見ていた。ところがゲートウェイは、トークンから取り出した
 * 値があるときにだけそのヘッダーを付ける:
 *
 * <pre>
 *   String role = extractRoleFromJwt(jwt);
 *   ...
 *   if (role != null) { mutated.header("User-Role", role); }
 * </pre>
 *
 * <p>つまり {@code role} クレームのないトークンで入ってくると<b>ヘッダーが付かず</b>、受け側は
 * それを「内部呼び出し」と読んで通してしまう。現在発行されるトークンには role が必ず入っているので
 * (JwtUtils が {@code user.getRole()} から埋め、その関係は NOT NULL である)実際に破られた
 * ことはない。しかし<b>構造がそれを保証していない</b> — クレーム名が変わったり、
 * 別の経路で発行されたトークンが現れたり、パースが失敗したりすれば、その日に静かに開いてしまう。
 *
 * <p>そこで<b>三つのヘッダーのうち一つでも</b>あれば外部リクエストとみなす。{@code User-Email} は
 * JWT subject なので常にあり、{@code User-Id} はなければゲートウェイがリクエスト自体を遮断する。
 * 一つが欠けても残りが残る。
 *
 * <p>🔒 <b>サービス間 mTLS</b>(docs/57 §16)が有効(enforce)なら、「ヘッダーなし」だけでは内部ではない —
 * {@link InternalCallerFilter} が、内部 CA のクライアント証明書を提示しなかったヘッダーなしのリクエストに
 * {@code User-Role: ROLE_ANONYMOUS} を付け、この判定がそれを「外部・管理者ではない」と読む。
 * ゲートウェイの公開ルートとクラスター内の他の Pod がここに引っかかる。
 *
 * <p>⚠️ この判定は<b>ゲートウェイがクライアントの身元ヘッダーを消す</b>という前提の上に成り立っている。
 * {@code JwtAuthorizationGatewayFilterFactory} と {@code IdentityHeaderScrubFilter} が
 * その役目を担う。どちらか一つでも無力化されればこの判定は覆る — クライアントがヘッダーを自分で
 * 付けて「外部」に見せかけるのは権限を<b>下げる</b>方向なので無害だが、ヘッダーを
 * 消す側が壊れれば {@code User-Role: ROLE_ADMIN} の一行で管理者になれてしまう。
 */
public final class InternalCallGuard {

    private InternalCallGuard() {
    }

    /**
     * 게이트웨이를 거쳐 온 요청인가.
     *
     * <p>[JA] ゲートウェイを経由して来たリクエストか。
     *
     * @param userRole  {@code User-Role} 헤더
     *                  [JA] {@code User-Role} ヘッダー
     * @param userEmail {@code User-Email} 헤더
     *                  [JA] {@code User-Email} ヘッダー
     * @param userId    {@code User-Id} 헤더
     *                  [JA] {@code User-Id} ヘッダー
     */
    public static boolean fromGateway(String userRole, String userEmail, String userId) {
        return present(userRole) || present(userEmail) || present(userId);
    }

    /** 이 역할이 관리자인가. {@code ADMIN} 과 {@code ROLE_ADMIN} 을 모두 받는다. */
    // [JA] このロールは管理者か。{@code ADMIN} と {@code ROLE_ADMIN} の両方を受け付ける。
    public static boolean isAdmin(String userRole) {
        if (!present(userRole)) {
            return false;
        }
        // Locale.ROOT — 기본 로캘이 터키어면 "admin".toUpperCase() 가 "ADMİN"(점 있는 İ)이 되어 관리자를 못 알아본다.
        String normalized = userRole.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.equals("ADMIN") || normalized.equals("ROLE_ADMIN");
    }

    /**
     * 내부 전용 엔드포인트에 들어와도 되는가.
     *
     * <p>내부 호출이거나(헤더 없음), 외부지만 관리자일 때만 참이다.
     *
     * <p>[JA] 内部専用エンドポイントに入ってよいか。
     *
     * <p>内部呼び出し(ヘッダーなし)であるか、外部だが管理者である場合にのみ真となる。
     */
    public static boolean allowed(String userRole, String userEmail, String userId) {
        return !fromGateway(userRole, userEmail, userId) || isAdmin(userRole);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
