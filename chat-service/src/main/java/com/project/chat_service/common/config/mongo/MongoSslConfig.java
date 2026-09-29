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
        ConnectionString connectionString = new ConnectionString(mongoProperties.getClient());
        boolean sslEnabled = connectionString.getSslEnabled() != null
                ? connectionString.getSslEnabled()
                : true;   // 명시하지 않으면 기존 동작(TLS 사용)을 유지한다 — 안전한 기본값

        if (!sslEnabled) {
            return MongoClients.create(baseSettings(connectionString).build());
        }

        // ⚠️ 인증서 경로가 비어 있으면 아래의 사설 CA 조립을 건너뛰고 **JVM 기본 신뢰 저장소**로
        //    TLS 를 켠다. 관리형 MongoDB(Atlas 등)가 바로 이 경우다 — mongodb+srv 는 TLS 가
        //    기본이고, 인증서는 공개 CA 로 발급되며, 클라이언트 인증서는 없다.
        //    이 분기가 없으면 Atlas 로 붙는 순간 FileNotFoundException 이거나(경로가 비었을 때),
        //    사설 CA 만 담긴 트러스트스토어로 검증에 실패한다(경로가 남아 있을 때).
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

            // .pem CA 인증서 설정 (신뢰할 수 있는 인증서)
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
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            FileInputStream keyStoreStream = new FileInputStream(keystoreFile);
            keyStore.load(keyStoreStream, keystorePassword.toCharArray());
            keyStoreStream.close();
                       
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keystorePassword.toCharArray());
            
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);  // 이 부분이 중요

            // KeyManagerFactory와 TrustManagerFactory 모두 사용하여 상호 TLS 설정
            //sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

            // MongoClientSettings에 SSL Context 설정
            MongoClientSettings settings = baseSettings(connectionString)
                .applyToSslSettings(builder -> builder.enabled(true).context(sslContext))
                .build();

            return MongoClients.create(settings);


        } catch (Exception e) {
            throw new IllegalStateException("Failed to configure MongoDB with SSL.", e);
        }
    }

    /** TLS 관련 설정을 제외한 공통 클라이언트 설정. TLS 사용/미사용 경로가 같은 값을 공유한다. */
    private MongoClientSettings.Builder baseSettings(ConnectionString connectionString) {
        return MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .applyToConnectionPoolSettings(builder -> builder.maxConnectionIdleTime(10L, TimeUnit.SECONDS))
                .readConcern(ReadConcern.DEFAULT)
                .writeConcern(WriteConcern.MAJORITY)
                .readPreference(ReadPreference.primary());
    }
   

}
