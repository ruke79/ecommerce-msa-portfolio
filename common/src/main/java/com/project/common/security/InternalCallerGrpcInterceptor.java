package com.project.common.security;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;

/**
 * gRPC 판 {@link InternalCallerFilter} — 서비스 간 mTLS 의 호출자 판정을 gRPC 서버에도 똑같이 건다(docs/66).
 *
 * <h3>왜 따로 필요한가</h3>
 *
 * <p>{@link InternalCallerFilter} 는 서블릿 필터라 Tomcat 앞에서만 돈다. gRPC 서버(Netty, 별도 포트)에는 걸리지 않는다 — 이것이
 * 없으면 <b>클러스터 안의 아무 파드나</b> 재고 예약을 부를 수 있다. HTTP 쪽이 막아 둔 것을 옆문이 여는 셈이다.
 *
 * <h3>무엇을 재사용하나</h3>
 *
 * <p>판정 자체(내부 CA 서명 · 허용 주체 · 경로 규칙 · 모드)는 {@link InternalCallerFilter} 의 것을 그대로 부른다. gRPC 메서드의
 * HTTP/2 경로는 {@code /ecommerce.stock.v1.StockReservation/Reserve} 모양이라, {@code INTERNAL_AUTH_PATH_RULES} 의 경로 앞부분
 * 규칙({@code /ecommerce.stock.v1.StockReservation/=order-service})이 <b>그대로</b> 맞는다. 규칙은 한 곳에만 있다.
 *
 * <h3>HTTP 와 다른 점</h3>
 *
 * <ul>
 *   <li>신원 헤더(User-*)를 보지 않는다 — gRPC 포트는 게이트웨이 뒤에 있지 않다(외부 요청이 올 길이 없다). 그래서 "헤더가 있으면 외부"
 *       분기가 없다.</li>
 *   <li>막을 때 {@code ROLE_ANONYMOUS} 로 바꿔 넘기지 않고 <b>바로 끊는다</b> — 인증서 없음은 {@code UNAUTHENTICATED}, 규칙 위반은
 *       {@code PERMISSION_DENIED}. 뒤에서 다시 판정할 인가 계층이 없으므로.</li>
 * </ul>
 *
 * <p>통과한 호출의 서비스 이름은 {@link #CALLER} 로 꺼낼 수 있다.
 */
// [JA] gRPC 版 InternalCallerFilter — サービス間 mTLS の呼び出し元判定を gRPC サーバーにも同じくかける(docs/66)。
//      サーブレットフィルターは Tomcat の前でしか動かず、gRPC サーバー(Netty・別ポート)にはかからない。これがなければ
//      クラスター内の任意の Pod が在庫予約を呼べてしまう。判定そのものは InternalCallerFilter のものをそのまま呼ぶ —
//      gRPC メソッドの HTTP/2 パスが「/パッケージ.サービス/メソッド」なので、INTERNAL_AUTH_PATH_RULES のパス接頭辞ルールが
//      そのまま合う。ルールは一か所だけにある。HTTP と違い身元ヘッダーは見ず(gRPC ポートはゲートウェイの後ろにない)、
//      遮断するときは即座に切る(証明書なし UNAUTHENTICATED、ルール違反 PERMISSION_DENIED)。
@Slf4j
public class InternalCallerGrpcInterceptor implements ServerInterceptor {

    /** 통과한 호출의 서비스 이름(인증서 CN). mode=off 이거나 observe 에서 인증서 없이 통과했으면 비어 있다. */
    public static final Context.Key<String> CALLER = Context.key("internal-caller");

    private final InternalCallerFilter policy;

    public InternalCallerGrpcInterceptor(InternalCallerFilter policy) {
        this.policy = policy;
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
            ServerCallHandler<Q, R> next) {
        if (policy.mode() == InternalCallerFilter.Mode.OFF) {
            return next.startCall(call, headers);
        }
        String path = "/" + call.getMethodDescriptor().getFullMethodName();
        String caller = policy.callerOf(peerChain(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION)));

        if (caller != null) {
            String why = policy.policyViolation(caller, path);
            if (why == null) {
                policy.noteCaller(caller, path);
                return Contexts.interceptCall(Context.current().withValue(CALLER, caller), call, headers, next);
            }
            log.warn("[mTLS rule] {} — caller={} gRPC {}", why, caller, path);
            if (policy.mode() == InternalCallerFilter.Mode.OBSERVE
                    || policy.rulesMode() != InternalCallerFilter.Mode.ENFORCE) {
                return Contexts.interceptCall(Context.current().withValue(CALLER, caller), call, headers, next);
            }
            return refuse(call, Status.PERMISSION_DENIED.withDescription("caller not allowed for " + path));
        }

        if (policy.mode() == InternalCallerFilter.Mode.OBSERVE) {
            log.warn("[mTLS observe] gRPC 내부 호출에 내부 인증서가 없다 — enforce 면 거절된다. {}", path);
            return next.startCall(call, headers);
        }
        return refuse(call, Status.UNAUTHENTICATED.withDescription("internal client certificate required"));
    }

    private static <Q, R> ServerCall.Listener<Q> refuse(ServerCall<Q, R> call, Status status) {
        call.close(status, new Metadata());
        return new ServerCall.Listener<>() {
        };
    }

    /** TLS 세션이 받은 클라이언트 인증서 체인. 평문 연결이거나 인증서를 내지 않았으면 null. */
    static X509Certificate[] peerChain(SSLSession session) {
        if (session == null) {
            return null;
        }
        try {
            Certificate[] certs = session.getPeerCertificates();
            X509Certificate[] chain = new X509Certificate[certs.length];
            for (int i = 0; i < certs.length; i++) {
                if (!(certs[i] instanceof X509Certificate x509)) {
                    return null;
                }
                chain[i] = x509;
            }
            return chain;
        } catch (SSLPeerUnverifiedException e) {
            return null;   // 클라이언트 인증서를 내지 않았다(clientAuth=OPTIONAL)
        }
    }
}
