package com.project.common.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 서비스 간 mTLS — "내부 호출" 을 <b>신원 헤더가 없다</b>가 아니라 <b>내부 CA 의 클라이언트 인증서를 냈다</b>로 판정한다
 * (docs/57 §16).
 *
 * <h3>왜</h3>
 *
 * <p>{@link InternalCallGuard} 는 "게이트웨이가 붙인 신원 헤더가 없으면 내부 호출" 로 읽는다. 그런데 신원 헤더가 없는 요청은
 * 내부 호출 말고도 있다 — <b>게이트웨이의 공개 라우트</b>(로그인 없는 카탈로그 읽기·웹훅)와 <b>클러스터 안의 아무 파드</b>.
 * 지금 막혀 있는 것은 내부 API 가 우연히 공개 라우트의 메서드·경로 밖에 있어서다(카탈로그 내부 API 는 POST 라 JWT 라우트를
 * 탄다). 라우트 하나가 바뀌면 조용히 열린다.
 *
 * <h3>무엇을 하나</h3>
 *
 * <p>신원 헤더가 <b>없는</b> 요청만 본다(있으면 게이트웨이가 토큰을 검증하고 붙인 외부 요청이다 — 그대로 둔다):
 * <ul>
 *   <li>TLS 에서 받은 클라이언트 인증서가 <b>내부 CA 로 서명</b>됐고(유효 기간 안, 허용 주체 목록이 있으면 그 안) — 내부 호출.
 *       손대지 않는다.</li>
 *   <li>아니면(인증서 없음·다른 CA) — {@code enforce} 모드에서 {@code User-Role: ROLE_ANONYMOUS} 를 붙여 넘긴다. 그러면
 *       {@link InternalCallGuard#fromGateway} 가 "외부" 로, {@link InternalCallGuard#isAdmin} 이 "관리자 아님" 으로 읽어
 *       내부 전용 엔드포인트가 전부 403 이 된다. 호출 자리 수십 곳을 고치지 않고 판정의 입력을 바로잡는다.</li>
 * </ul>
 *
 * <p>인증서의 CA 검증은 TLS 층(서버의 신뢰 저장소)에만 맡기지 않고 <b>여기서 다시 한다</b> — JVM 기본 신뢰 저장소에 공개 CA 가
 * 들어 있으면, 공개 CA 가 발급한 클라이언트 인증서도 TLS 는 통과한다.
 *
 * <h3>누가 무엇을 부르나 — 허용 주체·경로 규칙 (docs/57 §16 서비스별 신원)</h3>
 *
 * <p>서비스마다 자기 인증서(CN=서비스 이름)를 낸다. 그래서 "내부인가" 에서 한 걸음 더 — "<b>어느 서비스</b>인가" 를 본다:
 * <ul>
 *   <li>{@code allowed-subjects} — 내부 호출로 받을 CN 목록. 공유 인증서(CN=localhost — 게이트웨이도 서버 인증서로 갖고 있다)를
 *       빼면, 게이트웨이가 뚫려도 그 키로 내부 호출을 흉내 낼 수 없다.</li>
 *   <li>{@code path-rules} — 경로 앞부분마다 부를 수 있는 서비스(예: 구매 집계는 user-service 만). 가장 긴 앞부분이 이긴다.</li>
 *   <li>{@code rules-mode} — observe 면 어긋난 호출을 {@code [mTLS rule]} 로 남기고 통과시킨다(규칙을 켜기 전 확인). enforce 면
 *       내부가 아닌 것으로 본다(403).</li>
 * </ul>
 * 처음 보는 (호출한 서비스, 경로) 짝은 {@code [mTLS caller]} 로 한 번 남긴다 — 규칙을 실제 트래픽에서 만든다.
 *
 * <h3>모드 — {@code internal-auth.mode}</h3>
 * <ul>
 *   <li>{@code off}(기본) — 아무것도 하지 않는다. 로컬 개발·인증서 없는 환경.</li>
 *   <li>{@code observe} — 판정만 하고 막지 않는다. 내부 경로({@code internal-auth.watch-paths})에 인증서 없이 온 요청을
 *       WARN 으로 남긴다 — 켜기 전에 "내부 호출이 모두 인증서를 내는가" 를 확인하는 단계.</li>
 *   <li>{@code enforce} — 위처럼 표시한다.</li>
 * </ul>
 *
 * <p>[JA] サービス間 mTLS — 「内部呼び出し」を<b>身元ヘッダーがない</b>ことではなく、<b>内部 CA のクライアント証明書を
 * 提示した</b>ことで判定する(docs/57 §16)。
 *
 * <h3>なぜ</h3>
 *
 * <p>{@link InternalCallGuard} は「ゲートウェイが付けた身元ヘッダーがなければ内部呼び出し」と読む。ところが身元ヘッダーの
 * ないリクエストは内部呼び出し以外にもある — <b>ゲートウェイの公開ルート</b>(ログイン不要のカタログ参照・Webhook)と
 * <b>クラスター内の任意の Pod</b>。今塞がっているのは、内部 API がたまたま公開ルートのメソッド・パスの外にあるからに
 * すぎない(カタログの内部 API は POST なので JWT ルートを通る)。ルートが一つ変われば静かに開いてしまう。
 *
 * <h3>何をするか</h3>
 *
 * <p>身元ヘッダーが<b>ない</b>リクエストだけを見る(あればゲートウェイがトークンを検証して付けた外部リクエストである —
 * そのままにする):
 * <ul>
 *   <li>TLS で受け取ったクライアント証明書が<b>内部 CA で署名</b>されており(有効期間内、許可主体リストがあればその中)
 *       — 内部呼び出し。手を加えない。</li>
 *   <li>そうでなければ(証明書なし・別の CA) — {@code enforce} モードでは {@code User-Role: ROLE_ANONYMOUS} を付けて
 *       渡す。すると {@link InternalCallGuard#fromGateway} が「外部」、{@link InternalCallGuard#isAdmin} が
 *       「管理者ではない」と読み、内部専用エンドポイントはすべて 403 になる。数十箇所の呼び出し元を直さずに、
 *       判定の入力を正す。</li>
 * </ul>
 *
 * <p>証明書の CA 検証は TLS 層(サーバーのトラストストア)だけに任せず、<b>ここで改めて行う</b> — JVM デフォルトの
 * トラストストアに公開 CA が入っていれば、公開 CA が発行したクライアント証明書も TLS は通過してしまう。
 *
 * <h3>誰が何を呼ぶか — 許可主体・パスルール (docs/57 §16 サービスごとの身元)</h3>
 *
 * <p>サービスごとに自分の証明書(CN=サービス名)を提示する。そこで「内部か」から一歩進んで、「<b>どのサービス</b>か」を見る:
 * <ul>
 *   <li>{@code allowed-subjects} — 内部呼び出しとして受け付ける CN のリスト。共有証明書(CN=localhost — ゲートウェイも
 *       サーバー証明書として持っている)を外せば、ゲートウェイが破られてもその鍵で内部呼び出しを装うことはできない。</li>
 *   <li>{@code path-rules} — パスの接頭辞ごとに、呼び出せるサービス(例: 購入集計は user-service のみ)。
 *       最も長い接頭辞が勝つ。</li>
 *   <li>{@code rules-mode} — observe なら違反した呼び出しを {@code [mTLS rule]} として記録して通す(ルールを
 *       有効にする前の確認)。enforce なら内部ではないものとみなす(403)。</li>
 * </ul>
 * 初めて見る (呼び出したサービス, パス) の組は {@code [mTLS caller]} として一度だけ記録する — ルールを実際の
 * トラフィックから作る。
 *
 * <h3>モード — {@code internal-auth.mode}</h3>
 * <ul>
 *   <li>{@code off}(デフォルト) — 何もしない。ローカル開発・証明書のない環境。</li>
 *   <li>{@code observe} — 判定だけして遮断しない。内部パス({@code internal-auth.watch-paths})に証明書なしで来た
 *       リクエストを WARN で記録する — 有効化する前に「内部呼び出しがすべて証明書を提示しているか」を確認する段階。</li>
 *   <li>{@code enforce} — 上記のとおりマークする。</li>
 * </ul>
 */
@Slf4j
public class InternalCallerFilter extends OncePerRequestFilter {

    public enum Mode { OFF, OBSERVE, ENFORCE }

    /** 내부 호출이 아닌 헤더 없는 요청에 붙이는 역할 — 관리자도 아니고 "헤더 없음" 도 아니게 만든다. */
    // [JA] 内部呼び出しではないヘッダーなしリクエストに付けるロール — 管理者でもなく「ヘッダーなし」でもない状態にする。
    public static final String ANONYMOUS_ROLE = "ROLE_ANONYMOUS";
    /** 확인된 내부 호출이면 호출한 쪽 인증서의 CN 을 이 요청 속성에 둔다. */
    // [JA] 確認済みの内部呼び出しなら、呼び出し側証明書の CN をこのリクエスト属性に置く。
    public static final String CALLER_ATTRIBUTE = "internal.caller";

    static final String CERT_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
    /** ops/37 의 대조 호출 표식 — observe 로그만 가른다. 인가에는 쓰지 않는다(위조해도 얻는 것이 없다). */
    // [JA] ops/37 の対照呼び出しの目印 — observe ログを分けるだけ。認可には使わない(偽造しても得るものはない)。
    static final String PROBE_HEADER = "X-Mtls-Probe";
    static final String PROBE_VALUE = "ops-37";
    private static final String ROLE = "User-Role";

    /** 경로 앞부분 하나와 그것을 부를 수 있는 서비스(CN)들. */
    // [JA] パスの接頭辞一つと、それを呼び出せるサービス(CN)群。
    public record PathRule(String prefix, Set<String> callers) {
    }

    private static final int MAX_SEEN_CALLERS = 1000;

    private final Mode mode;
    private final X509Certificate ca;
    private final Set<String> allowedSubjects;
    private final List<String> watchPaths;
    /** 앞부분이 긴 것부터 — 가장 구체적인 규칙이 이긴다. */
    // [JA] 接頭辞が長いものから — 最も具体的なルールが勝つ。
    private final List<PathRule> rules;
    private final Mode rulesMode;
    private final Set<String> seenCallers = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public InternalCallerFilter(Mode mode, X509Certificate ca, Set<String> allowedSubjects, List<String> watchPaths) {
        this(mode, ca, allowedSubjects, watchPaths, List.of(), Mode.ENFORCE);
    }

    public InternalCallerFilter(Mode mode, X509Certificate ca, Set<String> allowedSubjects, List<String> watchPaths,
            List<PathRule> rules, Mode rulesMode) {
        this.mode = mode;
        this.ca = ca;
        this.allowedSubjects = allowedSubjects == null ? Set.of() : allowedSubjects;
        this.watchPaths = watchPaths == null ? List.of() : watchPaths;
        this.rules = rules == null ? List.of() : rules.stream()
                .sorted(java.util.Comparator.comparingInt((PathRule r) -> r.prefix().length()).reversed())
                .toList();
        this.rulesMode = rulesMode == null ? Mode.ENFORCE : rulesMode;
    }

    /** PEM 인증서 파일을 읽는다. 없거나 읽지 못하면 null. */
    // [JA] PEM 証明書ファイルを読む。存在しないか読めなければ null。
    public static X509Certificate loadCa(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            Path file = Path.of(path);
            if (!Files.isReadable(file)) {
                return null;
            }
            try (InputStream in = Files.newInputStream(file)) {
                return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
        } catch (Exception e) {   // 잘못된 경로(InvalidPathException)·깨진 PEM 도 "없음" 이다 ／ [JA] 不正パス(InvalidPathException)・壊れた PEM も「なし」
            log.error("내부 CA 인증서를 읽지 못했다: {} ({})", path, e.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (mode == Mode.OFF || hasIdentity(request)) {
            chain.doFilter(request, response);
            return;
        }
        String caller = verifiedCaller(request);
        if (caller != null) {
            String uri = request.getRequestURI();
            String why = policyViolation(caller, uri);
            if (why == null) {
                noteCaller(caller, uri);
                request.setAttribute(CALLER_ATTRIBUTE, caller);
                chain.doFilter(request, response);
                return;
            }
            log.warn("[mTLS rule] {} — caller={} {} {}", why, caller, request.getMethod(), uri);
            if (mode == Mode.OBSERVE || rulesMode != Mode.ENFORCE) {
                request.setAttribute(CALLER_ATTRIBUTE, caller);
                chain.doFilter(request, response);
                return;
            }
            chain.doFilter(new AnonymousCaller(request), response);
            return;
        }
        if (mode == Mode.OBSERVE) {
            if (PROBE_VALUE.equals(request.getHeader(PROBE_HEADER))) {
                // ops/37 의 "인증서 없이" 대조 호출 — 경고로 남기면 다음 점검이 그것을 센다. 로그만 다르다(판정과 무관).
                // [JA] ops/37 の「証明書なし」対照呼び出し — 警告として残すと次回の点検がそれを数えてしまう。
                //      ログが異なるだけである(判定とは無関係)。
                log.info("[mTLS probe] 인증서 없는 대조 호출 {} {}", request.getMethod(), request.getRequestURI());
            } else if (watched(request.getRequestURI())) {
                log.warn("[mTLS observe] 내부 경로에 내부 인증서 없이 온 요청 — enforce 면 거절된다. {} {}",
                        request.getMethod(), request.getRequestURI());
            }
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(new AnonymousCaller(request), response);
    }

    /** 내부 CA 로 서명된 유효한 클라이언트 인증서면 그 CN(없으면 주체 전체), 아니면 null. */
    // [JA] 内部 CA で署名された有効なクライアント証明書ならその CN(なければ主体全体)、そうでなければ null。
    String verifiedCaller(HttpServletRequest request) {
        Object attr = request.getAttribute(CERT_ATTRIBUTE);
        return attr instanceof X509Certificate[] chain ? callerOf(chain) : null;
    }

    /**
     * 인증서 체인에서 호출한 서비스 — 내부 CA 로 서명된 유효한 체인이면 CN, 아니면 null. HTTP(위)와 gRPC
     * ({@link InternalCallerGrpcInterceptor})가 같은 판정을 쓴다 — 규칙을 두 곳에 적지 않는다(docs/66).
     */
    // [JA] 証明書チェーンから呼び出したサービス — 内部 CA で署名された有効なチェーンなら CN、そうでなければ null。
    //      HTTP(上)と gRPC(InternalCallerGrpcInterceptor)が同じ判定を使う — ルールを二か所に書かない(docs/66)。
    String callerOf(X509Certificate[] chain) {
        if (ca == null || chain == null || chain.length == 0) {
            return null;
        }
        try {
            // 사슬의 각 고리를 위로 검증하고, 맨 위를 내부 CA 로 검증한다(중간 CA 가 없으면 leaf 를 바로 CA 로).
            // [JA] チェーンの各リンクを上位に向かって検証し、最上位を内部 CA で検証する
            //      (中間 CA がなければ leaf を直接 CA で)。
            for (int i = 0; i < chain.length; i++) {
                chain[i].checkValidity();
                if (i + 1 < chain.length) {
                    chain[i].verify(chain[i + 1].getPublicKey());
                }
            }
            X509Certificate top = chain[chain.length - 1];
            if (!top.equals(ca)) {
                top.verify(ca.getPublicKey());
            }
        } catch (Exception e) {
            return null;
        }
        String cn = commonName(chain[0]);
        return cn == null ? chain[0].getSubjectX500Principal().getName() : cn;
    }

    /** 내부 CA 가 서명한 호출이지만 이 경로를 부를 수 없는가 — 이유, 부를 수 있으면 null. */
    // [JA] 内部 CA が署名した呼び出しだが、このパスを呼び出せないか — その理由。呼び出せるなら null。
    String policyViolation(String caller, String uri) {
        if (!allowedSubjects.isEmpty() && !allowedSubjects.contains(caller)) {
            return "허용 주체가 아니다";
        }
        String u = uri == null ? "" : uri;
        for (PathRule rule : rules) {
            if (u.startsWith(rule.prefix())) {
                return rule.callers().contains(caller) ? null : "이 경로를 부를 수 없다(" + rule.prefix() + ")";
            }
        }
        return null;
    }

    /** 처음 보는 (호출한 서비스, 경로) 짝을 한 번 남긴다 — 숫자 조각은 {id} 로 묶는다. 규칙을 실제 트래픽에서 만든다. */
    // [JA] 初めて見る (呼び出したサービス, パス) の組を一度だけ記録する — 数字のセグメントは {id} にまとめる。
    //      ルールを実際のトラフィックから作る。
    Mode mode() {
        return mode;
    }

    Mode rulesMode() {
        return rulesMode;
    }

    void noteCaller(String caller, String uri) {
        String path = (uri == null ? "" : uri).replaceAll("/\\d+(?=/|$)", "/{id}");
        String key = caller + " " + path;
        if (seenCallers.size() < MAX_SEEN_CALLERS && seenCallers.add(key)) {
            log.info("[mTLS caller] caller={} path={}", caller, path);
        }
    }

    static String commonName(X509Certificate cert) {
        try {
            for (Rdn rdn : new LdapName(cert.getSubjectX500Principal().getName()).getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return String.valueOf(rdn.getValue());
                }
            }
        } catch (Exception ignored) {
            // 주체를 읽지 못하면 CN 없음
            // [JA] 主体を読めなければ CN なし
        }
        return null;
    }

    private static boolean hasIdentity(HttpServletRequest r) {
        return present(r.getHeader("User-Role")) || present(r.getHeader("User-Email")) || present(r.getHeader("User-Id"));
    }

    private boolean watched(String uri) {
        String u = uri == null ? "" : uri.toLowerCase(Locale.ROOT);
        return watchPaths.stream().anyMatch(u::contains);
    }

    private static boolean present(String v) {
        return v != null && !v.isBlank();
    }

    /** 헤더 없는 요청에 {@code User-Role: ROLE_ANONYMOUS} 를 붙인 모습. */
    // [JA] ヘッダーなしのリクエストに {@code User-Role: ROLE_ANONYMOUS} を付けた姿。
    static final class AnonymousCaller extends HttpServletRequestWrapper {
        AnonymousCaller(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return ROLE.equalsIgnoreCase(name) ? ANONYMOUS_ROLE : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return ROLE.equalsIgnoreCase(name) ? Collections.enumeration(List.of(ANONYMOUS_ROLE)) : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            if (names.stream().noneMatch(ROLE::equalsIgnoreCase)) {
                names.add(ROLE);
            }
            return Collections.enumeration(names);
        }
    }
}
