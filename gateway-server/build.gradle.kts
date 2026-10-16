

// 게이트웨이는 WebFlux + Reactor Netty 로만 돈다 — 서블릿 · Tomcat 을 클래스패스에서 뺀다.
// starter-web 은 세 곳에서 들어왔다: 루트 subprojects 블록(모든 모듈), common 의 implementation(런타임에 전이), 그리고 예전의
// spring-boot-starter-websocket. 서블릿이 있으면 Boot 가 서블릿 앱으로 떠 Gateway 자동 설정이 꺼지거나(web-application-type 을
// reactive 로 고정해도) 리액티브 서버 팩토리가 Tomcat 을 골라 WebSocket 업그레이드 빈을 Tomcat 구현으로 따로 맞춰야 했다.
// Netty 하나면 Gateway 자동 설정이 ReactorNetty 업그레이드 전략 · WebSocket 클라이언트를 직접 준다(NettyServerTest 가 지킨다).
// [JA] ゲートウェイは WebFlux + Reactor Netty だけで動く — サーブレット・Tomcat をクラスパスから外す。starter-web はルート subprojects・
//      common・旧 starter-websocket の三か所から入っていた。Netty だけなら Gateway が ReactorNetty の WebSocket 部品を自分で用意する。
configurations.configureEach {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-web")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-websocket")
    exclude(group = "org.apache.tomcat.embed")
    exclude(group = "org.springframework", module = "spring-webmvc")
}

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter")    
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j")
    implementation("org.springframework.cloud:spring-cloud-starter-gateway")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    //implementation("org.springframework.cloud:spring-cloud-starter-bus-amqp")
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    
    // RequestRateLimiter(게이트웨이 레이트리미터)의 백엔드 토큰 버킷 저장소
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Prometheus 스크레이프용 메트릭 레지스트리
    implementation("io.micrometer:micrometer-registry-prometheus")
    
    implementation(project(":common"))
    

   implementation(group = "io.jsonwebtoken", name = "jjwt-api", version= "0.12.7")
   implementation(group = "io.jsonwebtoken", name = "jjwt-jackson", version= "0.12.7")
   implementation(group = "io.jsonwebtoken", name = "jjwt-impl", version= "0.12.7")

}

