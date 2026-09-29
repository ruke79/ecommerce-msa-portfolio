# 1단계: 애플리케이션 빌드를 위한 환경 (JDK 포함)
# Maven 또는 Gradle을 사용하여 소스를 빌드하는 단계입니다.
# OpenJDK 17 기반의 경량화된 이미지 사용
FROM openjdk:17-jdk-slim AS builder
 
# JAR 파일을 복사할 디렉토리 생성
WORKDIR /app

ARG SERVICE_DIR
ARG SPRING_PROFILES_ACTIVE
ARG CONFIG_SERVER_URL

# Gradle 또는 Maven 빌드 파일 복사
# 프로젝트 구조에 맞게 수정하세요.
# 최상위 프로젝트의 Gradle Wrapper 및 빌드 파일 복사
COPY ./gradlew .
COPY ./gradle ./gradle

# build.gradle과 settings.gradle 파일 복사
COPY ./build.gradle .
COPY ./settings.gradle .

# ⭐ 빌드하려는 서비스 모듈 디렉토리 전체를 복사합니다.
# 이 명령으로 src 디렉토리와 함께 모듈의 build.gradle 파일도 복사됩니다.
COPY ${SERVICE_DIR} ./${SERVICE_DIR}
COPY common ./common


# 애플리케이션 빌드
# Gradle 기준으로 jar 파일을 생성합니다. Maven 사용 시 mvn clean package로 변경하세요.
# ⭐ 수정된 부분: Dockerfile 내부의 COPY 로직 변경으로 인해 RUN 명령을 수정해야 할 수 있습니다.
# 현재 구조에서는 RUN ./gradlew bootJar가 최상위 디렉토리에서 실행될 것입니다.
RUN ./gradlew :${SERVICE_DIR}:bootJar --build-cache --no-daemon



# 보안 강화를 위해 비-루트(non-root) 사용자로 실행
RUN addgroup --system spring && adduser --system --group spring
USER spring



# 2단계: 애플리케이션 실행을 위한 환경 (JRE만 포함)
# 최종 이미지는 이 단계에서 생성됩니다.
FROM eclipse-temurin:17-jre-jammy

# 빌드 인수를 실행 스테이지에서도 다시 정의 (builder 스테이지와 분리)
ARG SERVICE_DIR
ARG SPRING_PROFILES_ACTIVE
ARG CONFIG_SERVER_URL

# JAR 파일을 컨테이너 내부로 복사
# 프로젝트 루트에서 빌드한 후 해당 모듈의 target 디렉토리에 생성된 JAR 파일을 복사
ARG JAR_FILE=/app/${SERVICE_DIR}/build/libs/*.jar
# 비-루트로 내리기 전에 **쓸 수 있는 작업 디렉터리**를 마련한다.
# jar 를 상대 경로(app.jar)로 복사하므로 WORKDIR 는 반드시 COPY 앞에 와야 하고,
# 앱이 logging.file.path(./logs)에 쓰므로 이 디렉터리는 uid 1000 소유여야 한다.
# 둘 중 하나라도 어긋나면 "jar 를 못 찾음" 또는 "로그가 조용히 사라짐" 이 된다.
WORKDIR /app
COPY --from=builder ${JAR_FILE} app.jar
RUN mkdir -p /app/logs && chown -R 1000:1000 /app

# JVM 최적화 옵션을 추가하여 애플리케이션 시작 명령어를 정의
# -Xmx, -Xms 옵션으로 메모리 사용량을 명시적으로 설정할 수 있습니다.
# 컨테이너에 맞춰 자동으로 메모리/CPU를 최적화하는 -XX:+UseContainerSupport는 OpenJDK 8u191+부터 기본 설정입니다.
# JMX 설정을 위한 파일 복사 (필요한 경우)
# 아래 경로는 실제 프로젝트 구조에 맞게 수정해주세요.
# COPY ./jmxremote.password /app/jmxremote.password
# COPY ./jmxremote.access /app/jmxremote.access
# COPY ./resources/keystore.jks /app/resources/keystore.jks
# COPY ./resources/truststore.jks /app/resources/truststore.jks

# 동적으로 자바 실행 옵션을 설정하는 쉘 스크립트를 생성합니다.
# 이 스크립트는 ENTRYPOINT에 의해 직접 실행됩니다.
RUN echo "#!/bin/sh" > /usr/local/bin/run-app.sh && \
  echo "JAVA_OPTS=\"\"" >> /usr/local/bin/run-app.sh && \
  echo "if [ \"\$SPRING_PROFILES_ACTIVE\" = \"local\" ]; then" >> /usr/local/bin/run-app.sh && \
  echo "  echo \"Adding JMX options for local profile\"" >> /usr/local/bin/run-app.sh && \
  echo "  JAVA_OPTS=\"-Dcom.sun.management.jmxremote \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Dcom.sun.management.jmxremote.port=9010 \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Dcom.sun.management.jmxremote.ssl=true \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Dcom.sun.management.jmxremote.authenticate=true \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Dcom.sun.management.jmxremote.password.file=/app/jmxremote.password \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Dcom.sun.management.jmxremote.access.file=/app/jmxremote.access \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Djavax.net.ssl.keyStore=/app/resources/keystore.jks \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Djavax.net.ssl.keyStorePassword=keystore_password \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Djavax.net.ssl.trustStore=/app/resources/truststore.jks \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Djavax.net.ssl.trustStorePassword=truststore_password \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Djava.rmi.server.hostname=127.0.0.1\"" >> /usr/local/bin/run-app.sh && \
  echo "elif [ \"\$SPRING_PROFILES_ACTIVE\" = \"dev\" ]; then" >> /usr/local/bin/run-app.sh && \
  echo "  echo \"Adding JMX options for dev profile\"" >> /usr/local/bin/run-app.sh && \
  echo "  JAVA_OPTS=\"-Dcom.sun.management.jmxremote \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Dcom.sun.management.jmxremote.port=9010 \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Dcom.sun.management.jmxremote.ssl=true \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Dcom.sun.management.jmxremote.authenticate=true \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Dcom.sun.management.jmxremote.password.file=/app/jmxremote.password \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Dcom.sun.management.jmxremote.access.file=/app/jmxremote.access \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Djavax.net.ssl.keyStore=/app/resources/keystore.jks \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Djavax.net.ssl.keyStorePassword=keystore_password \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Djavax.net.ssl.trustStore=/app/resources/truststore.jks \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Djavax.net.ssl.trustStorePassword=truststore_password \\" >> /usr/local/bin/run-app.sh && \
  echo "    -Djava.rmi.server.hostname=127.0.0.1\"" >> /usr/local/bin/run-app.sh && \
  echo "fi" >> /usr/local/bin/run-app.sh && \
  echo "java \${JAVA_OPTS} -jar app.jar --spring.profiles.active=\${SPRING_PROFILES_ACTIVE} --spring.config.import=optional:configserver:\${CONFIG_SERVER_URL}" >> /usr/local/bin/run-app.sh && \
  chmod +x /usr/local/bin/run-app.sh

# 애플리케이션 시작을 위한 ENTRYPOINT 설정
# CMD는 비워둡니다. ENTRYPOINT가 쉘 스크립트를 직접 실행합니다.
# 비-루트 실행 — 반드시 **런타임 스테이지**에, 그리고 **숫자 UID** 로.
# 예전에는 USER spring 이 빌더 스테이지에만 있어 최종 이미지는 root 로 돌았고,
# 설령 이름을 런타임에 옮겨도 k8s 의 runAsNonRoot 는 숫자 UID 만 검증할 수 있어
# 'image will run as root' 로 파드 생성이 거부된다(ops/13 k3s 검증에서 실측).
RUN useradd --system --uid 1000 spring
# 애플리케이션이 logging.file.path(./logs)에 쓰므로, 비-루트로 내리기 전에
# **쓸 수 있는 작업 디렉터리**를 만들어 준다. 이게 없으면 uid 1000 은 / 에 쓰지 못해
# 파일 로깅이 조용히 사라진다(앱은 뜨지만 로그가 없다 — 가장 나쁜 실패 방식).
USER 1000

ENTRYPOINT ["/usr/local/bin/run-app.sh"]
CMD []

# 애플리케이션 포트 노출
EXPOSE 8188
# JMX 포트도 노출
EXPOSE 9010
