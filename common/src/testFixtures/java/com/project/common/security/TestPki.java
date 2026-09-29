package com.project.common.security;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * 테스트용 내부 PKI — JDK 의 keytool 만으로(외부 라이브러리 없이) 운영과 같은 모양의 인증서를 만든다(docs/57 §16 · docs/66).
 *
 * <ul>
 *   <li>{@link #ca} — 내부 CA(PEM). 운영의 {@code /app/resources/ca.crt} 자리.</li>
 *   <li>{@link #identity} — 그 CA 가 서명한 서비스 신원(PKCS#12, 키 + 체인). 운영의 {@code /app/identity/keystore.p12} 자리.
 *       CN = 서비스 이름, SAN = localhost(테스트 서버의 호스트 이름 확인이 통과하도록).</li>
 * </ul>
 *
 * <p>키스토어 비밀번호는 {@link #PASSWORD}. 파일은 호출자가 준 임시 디렉터리에 둔다.
 */
public final class TestPki {

    public static final String PASSWORD = "changeit";

    private final Path dir;

    public TestPki(Path dir) {
        this.dir = dir;
    }

    /** CA 를 만들고 PEM 경로를 돌려준다. */
    public Path ca(String alias, String cn) throws Exception {
        keytool("-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + cn,
                "-ext", "bc:c", "-validity", "2", "-keystore", alias + ".p12", "-storepass", PASSWORD, "-storetype", "PKCS12");
        keytool("-exportcert", "-rfc", "-alias", alias, "-file", alias + ".crt", "-keystore", alias + ".p12",
                "-storepass", PASSWORD);
        return dir.resolve(alias + ".crt");
    }

    /**
     * {@code caAlias} 가 서명한 신원 키스토어(PKCS#12, 키 + [자기 인증서, CA] 체인)를 만들고 경로를 돌려준다.
     * 서버 · 클라이언트 겸용(EKU serverAuth, clientAuth) — 운영의 신원 인증서(k8s/96 usages)와 같다.
     */
    public Path identity(String caAlias, String alias, String cn) throws Exception {
        String store = alias + ".p12";
        keytool("-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + cn,
                "-validity", "2", "-keystore", store, "-storepass", PASSWORD, "-storetype", "PKCS12");
        keytool("-certreq", "-alias", alias, "-file", alias + ".csr", "-keystore", store, "-storepass", PASSWORD);
        keytool("-gencert", "-alias", caAlias, "-infile", alias + ".csr", "-outfile", alias + ".crt", "-rfc",
                "-ext", "eku=serverAuth,clientAuth", "-ext", "san=dns:localhost,ip:127.0.0.1", "-validity", "2",
                "-keystore", caAlias + ".p12", "-storepass", PASSWORD);
        // 체인을 키스토어에 넣으려면 CA 를 먼저 신뢰 항목으로 들이고, 서명된 인증서로 키 항목을 바꾼다.
        keytool("-importcert", "-noprompt", "-alias", caAlias + "-trust", "-file", caAlias + ".crt",
                "-keystore", store, "-storepass", PASSWORD);
        keytool("-importcert", "-noprompt", "-alias", alias, "-file", alias + ".crt", "-keystore", store,
                "-storepass", PASSWORD);
        return dir.resolve(store);
    }

    public X509Certificate read(Path pem) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(pem)));
    }

    private void keytool(String... args) throws Exception {
        String bin = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        List<String> cmd = new ArrayList<>(List.of(bin));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool 실패: " + String.join(" ", args) + "\n" + out);
        }
    }
}
