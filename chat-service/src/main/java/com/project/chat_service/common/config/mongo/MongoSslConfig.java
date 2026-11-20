package com.project.chat_service.common.config.mongo;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.connection.TransportSettings;

import lombok.RequiredArgsConstructor;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyStore.LoadStoreParameter;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.DbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@Configuration
@RequiredArgsConstructor
@EnableMongoRepositories(basePackages = "com.project.chat_service.repository.mongo")
public class MongoSslConfig {
    private final MongoProperties mongoProperties;

    @Value("${spring.data.mongodb.cacert}")
    private String caCertificatePath; //your CA Certifcate decoded (starts with BEGIN CERTIFICATE)

    @Value("${spring.data.mongodb.keystore}")
    private String keystoreFile; //your CA Certifcate decoded (starts with BEGIN CERTIFICATE)

    @Value("${spring.data.mongodb.keystorePassword}")
    private String keystorePassword; //your CA Certifcate decoded (starts with BEGIN CERTIFICATE)
    
    // @Value("${spring.data.mongodb.truststore}") // 새로운 프로퍼티로 truststore 파일 경로 지정
    // private String truststoreFile;
    // @Value("${spring.data.mongodb.truststorePassword}") // truststore 비밀번호 지정
    // private String truststorePassword;
    // [JA] (コメントアウトされたコード) 新しいプロパティで truststore ファイルパスを指定 / truststore パスワードを指定


    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    @Bean
    public MongoTemplate mongoTemplate() {
        return new MongoTemplate(mongoClient(), mongoProperties.getName());
    }

    @Bean
    public MongoClient mongoClient() {

        // TLS 사용 여부는 연결 문자열(spring.data.mongodb.client 의 ?ssl=)을 따른다.
        // 예전에는 .enabled(true) 로 **하드코딩**돼 있어 URI 에 ssl=false 를 줘도 무시됐고,
        // 아래 인증서 로딩도 무조건 실행돼 TLS 를 쓰지 않는 환경(로컬 개발, TLS 를 사이드카/프록시에서
        // 종단하는 배포)에서는 chat-service 를 아예 띄울 수 없었다(docs/16 B-23).
        // Redisson 의 rediss:// 하드코딩(docs/14 B-14)과 같은 부류의 문제다.
        // [JA] TLS の使用有無は接続文字列(spring.data.mongodb.client の ?ssl=)に従う。
        //      以前は .enabled(true) で**ハードコード**されており URI に ssl=false を与えても無視され、
        //      下の証明書読み込みも無条件に実行されるため、TLS を使わない環境(ローカル開発、TLS をサイドカー/プロキシで
        //      終端するデプロイ)では chat-service をまったく起動できなかった(docs/16 B-23)。
        //      Redisson の rediss:// ハードコード(docs/14 B-14)と同じ類の問題である。
        ConnectionString connectionString = new ConnectionString(mongoProperties.getClient());
        boolean sslEnabled = connectionString.getSslEnabled() != null
                ? connectionString.getSslEnabled()
                : true;   // 명시하지 않으면 기존 동작(TLS 사용)을 유지한다 — 안전한 기본값 ／ [JA] 明示しなければ既存の動作(TLS 使用)を維持する — 安全なデフォルト

        if (!sslEnabled) {
            return MongoClients.create(baseSettings(connectionString).build());
        }

        // ⚠️ 인증서 경로가 비어 있으면 아래의 사설 CA 조립을 건너뛰고 **JVM 기본 신뢰 저장소**로
        //    TLS 를 켠다. 관리형 MongoDB(Atlas 등)가 바로 이 경우다 — mongodb+srv 는 TLS 가
        //    기본이고, 인증서는 공개 CA 로 발급되며, 클라이언트 인증서는 없다.
        //    이 분기가 없으면 Atlas 로 붙는 순간 FileNotFoundException 이거나(경로가 비었을 때),
        //    사설 CA 만 담긴 트러스트스토어로 검증에 실패한다(경로가 남아 있을 때).
        // [JA] ⚠️ 証明書パスが空なら下のプライベート CA の組み立てを飛ばし、**JVM デフォルトのトラストストア**で
        //      TLS を有効にする。マネージド MongoDB(Atlas など)がまさにこのケースである — mongodb+srv は TLS が
        //      デフォルトで、証明書は公開 CA で発行され、クライアント証明書はない。
        //      この分岐がないと Atlas に接続した瞬間に FileNotFoundException になるか(パスが空のとき)、
        //      プライベート CA だけが入ったトラストストアで検証に失敗する(パスが残っているとき)。
        if (!isSet(caCertificatePath) && !isSet(keystoreFile)) {
            return MongoClients.create(baseSettings(connectionString)
                    .applyToSslSettings(builder -> builder.enabled(true))
                    .build());
        }

        try {

            // TrustStore 설정: truststore 파일을 직접 로드
            // KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            // FileInputStream trustStoreStream = new FileInputStream(truststoreFile);
            // trustStore.load(trustStoreStream, truststorePassword.toCharArray());
            // trustStoreStream.close();
            // [JA] (コメントアウトされたコード) TrustStore 設定: truststore ファイルを直接ロードする
            //      (既定タイプの KeyStore を作り、truststoreFile を FileInputStream で開いて truststorePassword でロードし、ストリームを閉じる)。

            // .pem CA 인증서 설정 (신뢰할 수 있는 인증서)
            // [JA] .pem CA 証明書の設定(信頼できる証明書)
            FileInputStream caFileStream  = new FileInputStream(caCertificatePath);
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            X509Certificate caCert = (X509Certificate) certificateFactory.generateCertificate(caFileStream );
            caFileStream.close();
            

            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null);
            trustStore.setCertificateEntry("ca", caCert);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            
             // KeyStore 설정 (클라이언트 인증서 및 키)
             // [JA] KeyStore 設定(クライアント証明書と鍵)
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            FileInputStream keyStoreStream = new FileInputStream(keystoreFile);
            keyStore.load(keyStoreStream, keystorePassword.toCharArray());
            keyStoreStream.close();
                       
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keystorePassword.toCharArray());
            
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);  // 이 부분이 중요 ／ [JA] この部分が重要

            // KeyManagerFactory와 TrustManagerFactory 모두 사용하여 상호 TLS 설정
            //sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            // [JA] KeyManagerFactory と TrustManagerFactory の両方を使って相互 TLS を設定

            // MongoClientSettings에 SSL Context 설정
            // [JA] MongoClientSettings に SSL Context を設定
            MongoClientSettings settings = baseSettings(connectionString)
                .applyToSslSettings(builder -> builder.enabled(true).context(sslContext))
                .build();

            return MongoClients.create(settings);


        } catch (Exception e) {
            throw new IllegalStateException("Failed to configure MongoDB with SSL.", e);
        }
    }

    /** TLS 관련 설정을 제외한 공통 클라이언트 설정. TLS 사용/미사용 경로가 같은 값을 공유한다. */
    // [JA] TLS 関連設定を除いた共通クライアント設定。TLS 使用/不使用の経路が同じ値を共有する。
    private MongoClientSettings.Builder baseSettings(ConnectionString connectionString) {
        return MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToConnectionPoolSettings(builder -> builder.maxConnectionIdleTime(10L, TimeUnit.SECONDS))
                .readConcern(ReadConcern.DEFAULT)
                .writeConcern(WriteConcern.MAJORITY)
                .readPreference(ReadPreference.primary());
    }
   

}
