package com.project.common.grpc;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;

/**
 * gRPC 의 mTLS 설정 — 서비스 신원 인증서(docs/57 §16)를 그대로 쓴다(docs/66).
 *
 * <p><b>키는 PKCS#12 키스토어에서 읽는다.</b> cert-manager 가 만드는 {@code tls.key} 는 RSA 면 PKCS#1 형식인데, Netty 의 PEM
 * 읽기는 PKCS#8 만 받는다(PKCS#1 은 BouncyCastle 이 있어야 한다). 같은 Secret 의 {@code keystore.p12} 는 JVM 이 이미 읽고 있는
 * 파일이라(JAVA_TOOL_OPTIONS 의 javax.net.ssl.keyStore) 형식 문제가 없다.
 *
 * <p><b>신뢰는 내부 CA 하나만.</b> JVM 기본 신뢰 저장소(공개 CA 포함)를 쓰지 않는다 — 공개 CA 가 발급한 인증서가 내부 서비스인 척할 수
 * 없게. 서버 쪽 호출자 판정({@code InternalCallerGrpcInterceptor})도 내부 CA 로 다시 확인한다.
 */
// [JA] gRPC の mTLS 設定 — サービスの身元証明書をそのまま使う。鍵は PKCS#12 キーストアから読む(cert-manager の tls.key は
//      RSA なら PKCS#1 で、Netty の PEM 読み込みは PKCS#8 しか受けない)。信頼するのは内部 CA ひとつだけ(公開 CA を含む
//      JVM デフォルトのトラストストアは使わない)。
public final class GrpcTls {

    private GrpcTls() {
    }

    /**
     * 서버. 클라이언트 인증서는 <b>요청하되 강제하지 않는다</b>(OPTIONAL) — 없을 때의 처리(observe 면 기록 후 통과, enforce 면
     * UNAUTHENTICATED)는 인터셉터가 모드에 따라 정한다. TLS 층에서 끊으면 observe 단계에서 "누가 인증서 없이 부르나" 를 볼 수 없다.
     */
    public static SslContext server(String keyStorePath, String keyStorePassword, String caPemPath) throws Exception {
        return GrpcSslContexts.configure(SslContextBuilder.forServer(keyManagers(keyStorePath, keyStorePassword)))
                .trustManager(caFile(caPemPath))
                .clientAuth(ClientAuth.OPTIONAL)
                .build();
    }

    /** 클라이언트 — 자기 신원 인증서를 내고, 서버 인증서를 내부 CA 와 호스트 이름(dnsNames)으로 확인한다. */
    public static SslContext client(String keyStorePath, String keyStorePassword, String caPemPath) throws Exception {
        return GrpcSslContexts.forClient()
                .keyManager(keyManagers(keyStorePath, keyStorePassword))
                .trustManager(caFile(caPemPath))
                .build();
    }

    /** 설정이 TLS 를 켜라고 하는가 — 키스토어와 CA 가 둘 다 읽을 수 있어야 한다. */
    public static boolean configured(String keyStorePath, String caPemPath) {
        return readable(keyStorePath) && readable(caPemPath);
    }

    private static KeyManagerFactory keyManagers(String path, String password) throws Exception {
        char[] pw = password == null ? new char[0] : password.toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            store.load(in, pw);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, pw);
        return kmf;
    }

    private static File caFile(String path) {
        return new File(path);
    }

    private static boolean readable(String path) {
        return path != null && !path.isBlank() && Files.isReadable(Path.of(path));
    }
}
