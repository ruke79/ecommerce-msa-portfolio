package com.project.catalog_service.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 쓰기 요청의 역할(ROLE_ADMIN) 검사.
 *
 * <p>이 서비스에는 Spring Security 가 없다 — 인증은 게이트웨이의 JwtAuthorization 이
 * 끝내고, 검증된 신원을 {@code User-Id}/{@code User-Role} 헤더로 넘긴다. 그 헤더는
 * 게이트웨이 default-filters 가 외부 값을 무조건 지운 뒤 검증된 값만 다시 넣으므로
 * (application-local.yml 의 RemoveRequestHeader 주석 참조) 게이트웨이를 거친 요청에서는
 * 위조할 수 없다. 여기서는 그 역할 값으로 인가만 판정한다.
 *
 * <p>왜 필요한가: 게이트웨이의 catalog-service-write 라우트는 "로그인했는가"까지만 본다.
 * 로그인한 **일반 사용자**도 통과한다. 상품 등록·카테고리 생성·배치 잡 기동은 관리자
 * 전용이어야 하므로 역할 검사가 한 겹 더 필요하다. 실제로 이 서비스의 유일한
 * {@code @PreAuthorize} 는 주석 처리된 채 배포됐고, 사이트가 공개된 뒤 쓰기 API 전체가
 * 무인증으로 열려 있었다.
 *
 * <p>규칙 하나 더: {@code /api/content/admin/**} 은 반대로 <b>읽기까지</b> 관리자 전용이다.
 * 그 아래에 비공개 배너와 초안 공지 목록이 있어서, 쓰기만 막으면 GET 으로 미공개
 * 기획전의 존재와 시작 시각이 새어 나간다.
 *
 * <p>{@code PUT /api/product/rating} 은 <b>내부 전용</b>이다(docs/57 §3). 별점은 사람이 직접 매기는 값이 아니라
 * user-service 가 리뷰의 평균으로 계산해 보내는 값이다. 🐛 예전에는 "로그인만" 요구했다 — 그래서
 * <ul>
 *   <li>로그인한 아무나 아무 상품의 별점을 임의 값으로 덮어쓸 수 있었고,</li>
 *   <li>정작 신원 헤더 없이 오는 user-service 의 내부 호출은 아래 401 에 막혀, <b>리뷰 저장까지 롤백됐다</b>
 *       (별점 반영이 리뷰와 같은 트랜잭션이었다).</li>
 * </ul>
 * 이제 내부 호출(신원 헤더 없음)과 관리자만 통과한다({@code InternalCallGuard.allowed}). 게이트웨이의 쓰기 라우트는
 * 언제나 JWT 필터를 거치므로 밖에서 온 쓰기 요청에는 신원 헤더가 반드시 있다.
 *
 * <p>[JA] 書き込みリクエストのロール(ROLE_ADMIN)検査。
 *      このサービスには Spring Security がない — 認証はゲートウェイの JwtAuthorization が
 *      済ませ、検証済みのアイデンティティを {@code User-Id}/{@code User-Role} ヘッダで渡す。そのヘッダは
 *      ゲートウェイの default-filters が外部の値を無条件に消した後で検証済みの値だけを入れ直すので
 *      (application-local.yml の RemoveRequestHeader のコメント参照)ゲートウェイを経たリクエストでは
 *      偽造できない。ここではそのロールの値で認可だけを判定する。
 *      なぜ必要か: ゲートウェイの catalog-service-write ルートは「ログインしているか」までしか見ない。
 *      ログインした **一般ユーザー** も通る。商品登録・カテゴリ作成・バッチジョブの起動は管理者
 *      専用であるべきなので、ロール検査がもう一層必要である。実際、このサービスの唯一の
 *      {@code @PreAuthorize} はコメントアウトされたままデプロイされ、サイトが公開された後、書き込み API 全体が
 *      無認証で開いていた。
 *      もう 1 つの規則: {@code /api/content/admin/**} は逆に <b>読み取りまで</b> 管理者専用である。
 *      その下に非公開バナーと下書き告知の一覧があるので、書き込みだけ塞ぐと GET で未公開の
 *      企画展の存在と開始時刻が漏れる。
 *      {@code PUT /api/product/rating} は <b>内部専用</b> である(docs/57 §3)。星評価は人が直接付ける値ではなく、
 *      user-service がレビューの平均で計算して送る値である。🐛 以前は「ログインだけ」を要求していた — そのため
 *      · ログインした誰でも任意の商品の星評価を任意の値で上書きでき、
 *      · 肝心のアイデンティティヘッダなしで来る user-service の内部呼び出しは下の 401 に阻まれ、<b>レビューの保存までロールバックされた</b>
 *        (星評価の反映がレビューと同じトランザクションだった)。
 *      今は内部呼び出し(アイデンティティヘッダなし)と管理者だけが通る({@code InternalCallGuard.allowed})。ゲートウェイの書き込みルートは
 *      常に JWT フィルタを経るので、外から来た書き込みリクエストには必ずアイデンティティヘッダがある。
 */
@Slf4j
@Component
public class AdminWriteInterceptor implements HandlerInterceptor {

    static final String ROLE_HEADER = "User-Role";
    static final String USER_HEADER = "User-Id";
    static final String ADMIN = "ROLE_ADMIN";
    /** 이 접두사 아래는 메서드와 무관하게 관리자 전용이다(비공개 배너·초안 공지). */
    // [JA] この接頭辞の下はメソッドと無関係に管理者専用である(非公開バナー・下書き告知)。
    static final String ADMIN_CONTENT_PREFIX = "/api/content/admin";
    /** 별점 — 내부 전용(user-service 가 리뷰 평균을 보낸다). */
    // [JA] 星評価 — 内部専用(user-service がレビューの平均を送る)。
    static final String RATING_PATH = "/api/product/rating";
    /** 이 접두사 아래는 내부 전용이다 — 읽기도(docs/59). */
    // [JA] この接頭辞の下は内部専用である — 読み取りも(docs/59)。
    static final String INTERNAL_PREFIX = "/api/internal/";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String method = request.getMethod();
        String path = judgedPath(request);

        // 콘텐츠 관리 경로는 **읽기도** 관리자 전용이다. 그 아래에 비공개 배너·초안 공지
        // 목록이 있어서, 쓰기만 막으면 GET 으로 미공개 기획전의 존재와 시작 시각이
        // 그대로 새어 나간다. 경로 규칙이 곧 인가 규칙이므로 새 관리 엔드포인트는 반드시
        // 이 접두사 아래에 둘 것.
        // [JA] コンテンツ管理パスは **読み取りも** 管理者専用である。その下に非公開バナー・下書き告知の
        //      一覧があるので、書き込みだけ塞ぐと GET で未公開企画展の存在と開始時刻が
        //      そのまま漏れる。パス規則がそのまま認可規則なので、新しい管理エンドポイントは必ず
        //      この接頭辞の下に置くこと。
        boolean adminOnlyPath = path.startsWith(ADMIN_CONTENT_PREFIX) || path.startsWith(INTERNAL_PREFIX)
                || matchedAdminPattern(request);

        // 읽기는 공개다. CORS preflight(OPTIONS)도 여기서 막으면 안 된다.
        // [JA] 読み取りは公開である。CORS preflight(OPTIONS)もここで塞いではならない。
        if (!adminOnlyPath && !("POST".equals(method) || "PUT".equals(method)
                || "DELETE".equals(method) || "PATCH".equals(method))) {
            return true;
        }

        // preflight 는 신원 헤더를 달고 오지 않는다. 여기서 막으면 브라우저가 본 요청을
        // 보내지도 못한다.
        // [JA] preflight はアイデンティティヘッダを付けて来ない。ここで塞ぐとブラウザが本リクエストを
        //      送ることすらできない。
        if ("OPTIONS".equals(method)) {
            return true;
        }

        String userId = request.getHeader(USER_HEADER);
        String role = request.getHeader(ROLE_HEADER);

        // 내부 API(재고 예약 등, docs/59) — 메서드와 무관하게 내부 호출(신원 헤더 없음)·관리자만. 게이트웨이를 거친 요청에는
        // 언제나 신원 헤더가 있다.
        // [JA] 内部 API(在庫予約など、docs/59) — メソッドと無関係に内部呼び出し(アイデンティティヘッダなし)・管理者のみ。ゲートウェイを経たリクエストには
        //      常にアイデンティティヘッダがある。
        if (path.startsWith(INTERNAL_PREFIX)) {
            if (com.project.common.security.InternalCallGuard.allowed(role, request.getHeader("User-Email"), userId)) {
                return true;
            }
            log.warn("내부 API 외부 접근 거부. {} {} (userId={})", method, path, userId);
            return reject(response, HttpStatus.FORBIDDEN, "접근할 수 없습니다.");
        }

        // 별점 — 내부(리뷰 평균을 보내는 user-service)와 관리자만. 신원 헤더 검사보다 먼저 본다 — 내부 호출에는
        // 헤더가 없다.
        // [JA] 星評価 — 内部(レビュー平均を送る user-service)と管理者のみ。アイデンティティヘッダの検査より先に見る — 内部呼び出しには
        //      ヘッダがない。
        if (RATING_PATH.equals(path)) {
            if (com.project.common.security.InternalCallGuard.allowed(role, request.getHeader("User-Email"), userId)) {
                return true;
            }
            log.warn("별점 직접 변경 거부 — 별점은 리뷰로만 바뀐다. userId={}", userId);
            return reject(response, HttpStatus.FORBIDDEN, "별점은 리뷰를 통해서만 바뀝니다.");
        }

        // 게이트웨이를 안 거친(또는 라우트 설정이 깨진) 요청. 인증이 안 됐다는 뜻이므로
        // 401 — 게이트웨이가 정상이라면 이 분기는 나올 수 없다.
        // [JA] ゲートウェイを経ていない(またはルート設定が壊れた)リクエスト。認証されていないという意味なので
        //      401 — ゲートウェイが正常ならこの分岐は出ないはずである。
        if (userId == null || userId.isBlank()) {
            log.warn("쓰기 요청에 신원 헤더가 없다 — 게이트웨이를 거치지 않았거나 라우트가 깨졌다. {} {}", method, path);
            return reject(response, HttpStatus.UNAUTHORIZED, "로그인이 필요한 서비스입니다.");
        }

        if (!ADMIN.equals(role)) {
            // 값이 아니라 사실만 남긴다 — userId 는 숫자 id 라 그대로 남겨도 되지만
            // 역할 문자열은 클레임 그대로라 로그에 남길 이유가 없다.
            // [JA] 値ではなく事実だけを残す — userId は数値 id なのでそのまま残してもよいが、
            //      ロールの文字列はクレームそのままなのでログに残す理由がない。
            log.warn("관리자 아님 — 쓰기 거부. {} {} (userId={})", method, path, userId);
            return reject(response, HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
        }

        return true;
    }

    /** 디코딩 · {@code ;…} 제거 · {@code //} 정리를 하는 Spring 의 기본 설정 — 컨트롤러를 고르는 쪽과 같은 경로를 본다. */
    // [JA] デコード · {@code ;…} の除去 · {@code //} の整理を行う Spring の既定設定 — コントローラを選ぶ側と同じパスを見る。
    private static final org.springframework.web.util.UrlPathHelper PATHS = new org.springframework.web.util.UrlPathHelper();

    /**
     * 판정할 경로 — Spring MVC 가 핸들러를 고를 때 보는 것과 같게(퍼센트 디코딩, 세그먼트의 {@code ;…} 제거, {@code //} 정리).
     *
     * <p>🐛 예전에는 {@code getRequestURI()}(디코딩 전 원문)를 앞부분만 비교했다. 그런데 핸들러 매칭은 디코딩하고 {@code ;…} 를 뗀다 —
     * {@code /api/content/%61dmin/notices} · {@code /api/content;x=1/admin/banners} 는 접두사가 맞지 않아 "공개 읽기" 로
     * 통과했고, 컨트롤러에는 그대로 닿아 로그인한 일반 사용자가 초안 공지 · 비공개 배너 · 예정 기획전 · 재고 목록을 읽었다.
     *
     * <p>[JA] 判定するパス — Spring MVC がハンドラーを選ぶときと同じく、デコード · {@code ;…} 除去 · {@code //} 整理。以前は生の
     * URI を前方一致で比べ、エンコードや {@code ;} で管理者専用の読み取りを迂回できた。
     */
    static String judgedPath(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request);
        return path == null ? "" : path;
    }

    /** 두 번째 확인 — 이미 골라진 핸들러의 패턴이 관리자 경로인가(경로 정규화가 Spring 과 어긋나도 막히게). */
    // [JA] 2 回目の確認 — 既に選ばれたハンドラのパターンが管理者パスか(パスの正規化が Spring とずれても塞がるように)。
    private static boolean matchedAdminPattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null && pattern.toString().startsWith(ADMIN_CONTENT_PREFIX);
    }

    private boolean reject(HttpServletResponse response, HttpStatus status, String message)
            throws Exception {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // 응답 본문을 직접 쓰므로 ClientTextResponseAdvice 를 거치지 않는다 — 여기서 요청 언어로 바꾼다(docs/60 §4).
        // 문구는 이 클래스의 상수뿐이라 JSON 이스케이프가 필요 없다(따옴표·역슬래시 없음).
        // [JA] 応答本文を直接書くので ClientTextResponseAdvice を通らない — ここでリクエストの言語に変える(docs/60 §4)。
        //      文言はこのクラスの定数だけなので JSON エスケープは不要(引用符・バックスラッシュなし)。
        response.getWriter().write("{\"message\":\"" + com.project.common.i18n.ClientText.localize(message) + "\"}");
        return false;
    }
}
