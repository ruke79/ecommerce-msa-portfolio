package com.project.coupon_service.controller;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.project.common.constants.StatusMessages;
import com.project.common.dto.ProductQADto;
import com.project.common.response.MessageResponse;
import com.project.common.security.InternalCallGuard;
import com.project.coupon_service.dto.CouponDto;
import com.project.coupon_service.dto.request.CouponRequest;
import com.project.coupon_service.dto.response.CouponResponse;
import com.project.coupon_service.dto.request.CouponApplyRequest;
import com.project.coupon_service.model.Coupon;
import com.project.coupon_service.service.CouponService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequestMapping("api/coupon")
@RestController
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;


    /**
     * 쿠폰 발급 — <b>관리자 전용</b>.
     *
     * <p>🔒 예전에는 권한 검사가 없었다. 게이트웨이는 로그인만 확인하므로({@code JwtAuthorization}), 로그인한
     * 아무나 <b>자기 id 로 할인율을 마음대로 정한 쿠폰</b>을 만들 수 있었다. 주문 총액은 서버가 그 쿠폰으로
     * 계산하므로 결제 금액 대조도 통과한다 — 금액 검증이 막아 주지 못하는 종류다. 화면(프론트)은 이 경로를
     * 부르지 않는다. 일반 사용자의 발급은 조건을 서버가 판정하는 {@code /events/{slug}/claim} 뿐이다.
     *
     * <p>[JA] クーポン発行 — <b>管理者専用</b>。
     * <p>🔒 以前は権限チェックがなかった。ゲートウェイはログインだけを確認するので({@code JwtAuthorization})、ログインした誰でも<b>自分の id で割引率を勝手に決めたクーポン</b>を作れた。注文総額はサーバーがそのクーポンで計算するので決済金額の照合も通る — 金額検証では防げない種類だ。画面(フロント)はこの経路を呼ばない。一般ユーザーの発行は条件をサーバーが判定する {@code /events/{slug}/claim} だけだ。
     */
    @PostMapping("/add")
    ResponseEntity<?> addCoupon(@RequestBody CouponRequest request,
            @RequestHeader(value = "User-Role", required = false) String userRole) {

        if (!InternalCallGuard.isAdmin(userRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse("쿠폰을 발급할 권한이 없습니다."));
        }

        try {
            
            Coupon newCoupon = couponService.create(request);

            if (null != newCoupon) {

                return new ResponseEntity<>(CouponDto.convertToDto(newCoupon), HttpStatus.OK);
            }
            else 
                return new ResponseEntity<>(new MessageResponse(StatusMessages.COUPON_IS_EXISTED), HttpStatus.OK);
            
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse("Faield to create a coupon."));
        }
    }

    /**
     * 쿠폰 적용.
     *
     * <p>🔒 <b>누구의 쿠폰인지는 게이트웨이가 검증한 {@code User-Id} 헤더(토큰의 uid)가 정한다.</b>
     * 요청 본문의 {@code userEmail} 은 무시한다.
     *
     * <p>예전에는 본문의 이메일로 사용자를 찾았다. 로그인만 돼 있으면 본문에 <b>남의
     * 이메일</b>을 넣어 그 사람이 가진 미사용 쿠폰이 있는지 알아내고, 그 할인율로 계산된
     * 금액을 받고, 그 사람 id 로 쿠폰 사용 이벤트까지 발행시킬 수 있었다 — OWASP API 1위
     * BOLA(www-security 데모 시나리오 10)가 정확히 이 모양이다. 바로 아래
     * {@code /coupons} 는 이미 헤더를 쓰고 있었는데 이 엔드포인트만 빠져 있었다.
     *
     * <p>[JA] クーポン適用。🔒 <b>誰のクーポンかはゲートウェイが検証した {@code User-Id} ヘッダー(トークンの uid)が決める。</b>リクエスト本文の {@code userEmail} は無視する。
     * <p>以前は本文のメールでユーザーを探した。ログインさえしていれば本文に<b>他人のメール</b>を入れてその人の未使用クーポンの有無を知り、その割引率で計算された金額を受け取り、その人の id でクーポン使用イベントまで発行させられた — OWASP API 1 位 BOLA(www-security デモシナリオ 10)がまさにこの形だ。すぐ下の {@code /coupons} は既にヘッダーを使っていたが、このエンドポイントだけ抜けていた。
     */
    @PostMapping("/applycoupon")
    ResponseEntity<?> applyCoupon(@RequestBody CouponApplyRequest request,
            @RequestHeader(value = "User-Id", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("로그인이 필요합니다."));
        }

        CouponResponse response = couponService.applyCoupon(request, userId);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    /**
     * 얼마가 깎이는가 — <b>부수효과 없이</b>. order-service 가 주문 총액을 서버에서 다시
     * 계산할 때 부른다(docs/40 #10).
     *
     * <p>🔒 게이트웨이를 지난 요청에는 신원 헤더가 붙는다 — <b>헤더가 있다 = 외부에서 온
     * 요청</b>이고, 그때는 관리자만 허용한다. 서비스 간 Feign 호출은 게이트웨이를 거치지
     * 않아 헤더가 없다. 판정은 {@link InternalCallGuard} 가 한다.
     *
     * <p>⚠️ {@code User-Role} <b>하나만</b> 보면 안 된다. 게이트웨이는 토큰에서 뽑은 값이
     * 있을 때만 그 헤더를 붙이므로, role 클레임이 없는 토큰은 헤더 없이 도착해
     * "내부 호출" 로 통과한다. 세 헤더 중 하나라도 있으면 외부로 본다.
     *
     * <p>이 규칙이 없으면 로그인한 아무나 <b>남의 이메일</b>로 "그 사람이 이 쿠폰을
     * 가졌는지, 얼마가 깎이는지"를 물어볼 수 있다 — {@code /applycoupon} 이 본문 이메일을
     * 버리고 헤더만 쓰도록 고친 바로 그 구멍이 여기로 되살아난다.
     *
     * <p>[JA] いくら引かれるか — <b>副作用なしで</b>。order-service が注文総額をサーバーで再計算するときに呼ぶ(docs/40 #10)。
     * <p>🔒 ゲートウェイを通ったリクエストには身元ヘッダーが付く — <b>ヘッダーがある = 外部からのリクエスト</b>で、その場合は管理者だけ許可する。サービス間の Feign 呼び出しはゲートウェイを通らずヘッダーがない。判定は {@link InternalCallGuard} が行う。
     * <p>⚠️ {@code User-Role} <b>一つだけ</b>を見てはならない。ゲートウェイはトークンから取り出した値があるときだけそのヘッダーを付けるので、role クレームのないトークンはヘッダーなしで届き「内部呼び出し」として通ってしまう。三つのヘッダーのうち一つでもあれば外部とみなす。
     * <p>この規則がないと、ログインした誰でも<b>他人のメール</b>で「その人がこのクーポンを持っているか、いくら引かれるか」を尋ねられる — {@code /applycoupon} が本文のメールを捨ててヘッダーだけ使うよう直したまさにその穴がここで蘇る。
     */
    @PostMapping("/internal/quote")
    ResponseEntity<?> quote(
            // 누구의 견적인가 — 사용자 id(docs/48 §6-⑥). 예전에는 이메일을 받아 user-service 에 id 를 다시 물었다.
            // [JA] 誰の見積もりか — ユーザー id(docs/48 §6-⑥)。以前はメールを受け取り user-service に id を問い直していた。
            @RequestParam("userId") Long userId,
            @RequestParam("couponName") String couponName,
            // 주문이 읽은 장바구니 — 이것으로 계산한다(주문의 품목과 금액이 같은 장바구니에서 나온다). 없으면(옛 order) 여기서 읽는다.
            // [JA] 注文が読んだカート — これで計算する(注文の品目と金額が同じカートから出る)。なければ(古い order)ここで読む。
            @RequestBody(required = false) com.project.common.dto.CartDto cart,
            @RequestHeader(value = "User-Role", required = false) String callerRole,
            @RequestHeader(value = "User-Email", required = false) String callerEmail,
            @RequestHeader(value = "User-Id", required = false) String callerId) {

        // 헤더 하나만 보면 role 클레임이 없는 토큰이 "내부 호출" 로 통과한다
        // (InternalCallGuard 주석 참조).
        // [JA] ヘッダー一つだけを見ると role クレームのないトークンが「内部呼び出し」として通る
        //      (InternalCallGuard のコメント参照)。
        if (!InternalCallGuard.allowed(callerRole, callerEmail, callerId)) {
            log.warn("외부에서 쿠폰 견적 조회 시도를 차단");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse("접근할 수 없습니다."));
        }

        return ResponseEntity.ok(couponService.quote(couponName, userId, cart));
    }

    /**
     * 결제를 시작한다 — 이 주문이 쿠폰을 {@code holdSeconds} 동안 잡는다(V7). order-service 가 결제창을 열기 직전 · 입금 안내 때
     * 부른다. 결과는 늘 200 의 {@code {"result": CLAIMED | NOT_FOUND | USED | IN_PAYMENT_ELSEWHERE}} — 결제를 열지는 order 가 정한다.
     * 🔒 quote 와 같은 내부 호출 판정(경로 규칙은 order-service, B-80).
     *
     * <p>[JA] 決済を開始する — この注文がクーポンを {@code holdSeconds} の間押さえる(V7)。order-service が決済画面を開く直前 · 入金案内のときに
     * 呼ぶ。結果は常に 200 の {@code {"result": CLAIMED | NOT_FOUND | USED | IN_PAYMENT_ELSEWHERE}} — 決済を開くかは order が決める。
     * quote と同じ内部呼び出し判定(パス規則は order-service、B-80)。
     */
    @PostMapping("/internal/payment-claim")
    ResponseEntity<?> claimForPayment(
            @RequestParam("userId") Long userId,
            @RequestParam("couponName") String couponName,
            @RequestParam("orderId") Long orderId,
            @RequestParam("holdSeconds") long holdSeconds,
            @RequestHeader(value = "User-Role", required = false) String callerRole,
            @RequestHeader(value = "User-Email", required = false) String callerEmail,
            @RequestHeader(value = "User-Id", required = false) String callerId) {
        if (!InternalCallGuard.allowed(callerRole, callerEmail, callerId)) {
            log.warn("외부에서 쿠폰 결제 시작 시도를 차단");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse("접근할 수 없습니다."));
        }
        CouponService.ClaimOutcome outcome = couponService.claimForPayment(userId, couponName, orderId,
                java.time.Duration.ofSeconds(Math.max(0, holdSeconds)));
        return ResponseEntity.ok(java.util.Map.of("result", outcome.name()));
    }

    /**
     * 기획전 쿠폰을 받아 간다.
     *
     * <p>🔒 받는 사람은 <b>게이트웨이가 검증한 {@code User-Id}</b>(토큰의 uid)다. 경로나 본문에서
     * 받지 않는다 — 그러면 남의 계정으로 쿠폰을 발급시킬 수 있다.
     *
     * <p>조건(고객군·유효기간·적용 범위)은 catalog 에 물어본다. 여기서 기획전 상태를 다시
     * 판정하지 않는다 — 같은 규칙이 두 곳에 있으면 언젠가 어긋나고, 그때 이미 종료된
     * 기획전의 쿠폰이 계속 나간다.
     *
     * <p><b>"이미 받았다"는 200 이다.</b> 사용자 입장에서는 성공과 다르지 않다 — 버튼을 두
     * 번 눌렀거나 다른 탭에서 이미 받은 것이고, 오류를 띄우면 무엇이 잘못됐는지 찾게
     * 만든다. 실제로 못 받는 경우(대상 아님·기획전 종료)만 4xx 다.
     *
     * <p>[JA] 企画展クーポンを受け取る。🔒 受け取る人は<b>ゲートウェイが検証した {@code User-Id}</b>(トークンの uid)だ。パスや本文からは受け取らない — そうすると他人のアカウントでクーポンを発行させられる。
     * <p>条件(顧客グループ・有効期間・適用範囲)は catalog に問い合わせる。ここで企画展の状態を再判定しない — 同じ規則が二か所にあればいつかずれ、そのとき既に終了した企画展のクーポンが出続ける。
     * <p><b>「既に受け取った」は 200 だ。</b>ユーザーにとっては成功と変わらない — ボタンを二度押したか別タブで既に受け取ったもので、エラーを出すと何が悪いのか探させてしまう。実際に受け取れない場合(対象外・企画展終了)だけ 4xx。
     */
    @PostMapping("/events/{slug}/claim")
    ResponseEntity<?> claimEventCoupon(@PathVariable("slug") String slug,
            @RequestHeader(value = "User-Id", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("로그인이 필요합니다."));
        }

        CouponService.ClaimResult result = couponService.claimEventCoupon(slug, userId);

        return ResponseEntity
                .status(result.ok() ? HttpStatus.OK : HttpStatus.CONFLICT)
                .body(java.util.Map.of(
                        "status", result.status(),
                        "message", result.message()));
    }

    /**
     * 🔒 예전에는 {@code ?email=} 로 <b>남의 쿠폰 목록</b>을 그대로 볼 수 있었다. 신원은
     * 게이트웨이가 검증한 토큰에서만 온다({@code User-Id} 헤더 = 토큰의 uid — 클라이언트가 보낸 동명
     * 헤더는 게이트웨이가 지운다). 예전에는 {@code User-Email} 을 받아 user-service 에 id 를 다시 물었다.
     *
     * <p>[JA] 🔒 以前は {@code ?email=} で<b>他人のクーポン一覧</b>をそのまま見られた。身元はゲートウェイが検証したトークンからのみ来る({@code User-Id} ヘッダー = トークンの uid — クライアントが送った同名ヘッダーはゲートウェイが消す)。以前は {@code User-Email} を受け取り user-service に id を問い直していた。
     */
    @GetMapping("/coupons")
    ResponseEntity<?> getNotUsedCoupons(
            @RequestHeader(value = "User-Id", required = false) Long userId) {

            if (userId == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new MessageResponse("로그인이 필요합니다."));
            }

            List<CouponDto> coupons = couponService.getCouponsNotUsed(userId);

            if ( coupons != null) {

                List<String> result = coupons.stream().map(coupon -> {
                    return coupon.getName();
                }).collect(Collectors.toList());

                return new ResponseEntity<>(result, HttpStatus.OK);            
            }

            return new ResponseEntity<>(null, HttpStatus.OK);                            
    }

    /**
     * 쿠폰 삭제 — <b>관리자 전용</b>.
     *
     * <p>🔒 예전에는 권한 검사도 소유권 검사도 없었다. 로그인한 아무나 쿠폰 id 만 넣으면
     * 남의 쿠폰(또는 전체 프로모션 쿠폰)을 지울 수 있었다. id 를 훑으면 발행된 쿠폰을
     * 통째로 지우는 것도 가능하다.
     *
     * <p>[JA] クーポン削除 — <b>管理者専用</b>。
     * <p>🔒 以前は権限チェックも所有権チェックもなかった。ログインした誰でもクーポン id さえ入れれば他人のクーポン(または全体プロモーションクーポン)を消せた。id を総当たりすれば発行済みクーポンを丸ごと消すことも可能だった。
     */
    @DeleteMapping("/delete")
    ResponseEntity<?> deleteCoupon(@RequestParam("id") String id,
            @RequestHeader(value = "User-Role", required = false) String userRole) {

        if (userRole == null
                || !(userRole.trim().equalsIgnoreCase("ADMIN")
                     || userRole.trim().equalsIgnoreCase("ROLE_ADMIN"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse("쿠폰을 삭제할 권한이 없습니다."));
        }

        try {
            couponService.delete(id);

            return new ResponseEntity<>(new MessageResponse("succeeded to delete a coupon"), HttpStatus.OK);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new MessageResponse("Faield to create a coupon."));
        }
    }

    static final String UPDATE_UNSUPPORTED_MESSAGE = "쿠폰 수정은 지원하지 않습니다. 쿠폰을 지우고 다시 발급해 주세요.";

    /** 쿠폰 수정 — <b>관리자 전용</b>. 지금은 서비스가 아무것도 하지 않지만, 채워지는 순간 할인율을 바꾸는 경로다. */
    // [JA] クーポン修正 — <b>管理者専用</b>。今はサービスが何もしないが、実装された瞬間に割引率を変える経路になる。
    @PutMapping("/update")
    ResponseEntity<?> updateCoupon(@RequestBody CouponRequest request,
            @RequestHeader(value = "User-Role", required = false) String userRole) {

        if (!InternalCallGuard.isAdmin(userRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new MessageResponse("쿠폰을 수정할 권한이 없습니다."));
        }

        // 🐛 예전에는 서비스가 아무것도 하지 않고(본문은 주석) 200 에 빈 본문을 돌려줬다 — 관리자는 바뀐 줄 알았다(CP8). 지원하지 않는다고
        //    말한다. 이미 나간 쿠폰의 할인율을 바꾸는 것은 결제 중 · 쓴 주문과 어긋나므로, 필요하면 지우고 다시 발급한다.
        // [JA] 以前はサービスが何もせず(本体はコメント)200 に空の本文を返していた — 管理者は変わったと思っていた(CP8)。対応していないと
        //      伝える。既に出たクーポンの割引率を変えるのは決済中 · 使用済みの注文と食い違うので、必要なら削除して発行し直す。
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .body(new MessageResponse(UPDATE_UNSUPPORTED_MESSAGE));
    }


}
