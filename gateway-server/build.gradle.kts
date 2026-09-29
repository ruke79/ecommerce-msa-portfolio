

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter")    
    implementation("org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j")
    implementation("org.springframework.cloud:spring-cloud-starter-gateway")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    //implementation("org.springframework.cloud:spring-cloud-starter-bus-amqp")
    implementation("org.springframework.cloud:spring-cloud-starter-config")
    
    // RequestRateLimiter(게이트웨이 레이트리미터)의 백엔드 토큰 버킷 저장소
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Prometheus 스크레이프용 메트릭 레지스트리
    implementation("io.micrometer:micrometer-registry-prometheus")
    
    implementation(project(":common"))
    

   implementation(group = "io.jsonwebtoken", name = "jjwt-api", version= "0.12.7")
   implementation(group = "io.jsonwebtoken", name = "jjwt-jackson", version= "0.12.7")
   implementation(group = "io.jsonwebtoken", name = "jjwt-impl", version= "0.12.7")

}

