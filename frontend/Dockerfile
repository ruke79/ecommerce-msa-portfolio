# frontend용 Dockerfile (기존 저장소에 없어서 이번에 추가).
#
# 주의: React_APP_* 값들은 런타임 환경변수가 아니라 웹팩 빌드 시점에 번들에 박히는(bake-in) 값입니다
# (webpack.config.prod.js가 dotenv로 .env.production을 읽고 EnvironmentPlugin으로 치환).
# 따라서 이 값들은 k8s ConfigMap/Secret이 아니라 **이미지 빌드 시 --build-arg**로 전달해야 합니다.
#
# 빌드 예:
#   docker build \
#     --build-arg REACT_APP_API_URL=https://api.example.com \
#     --build-arg REACT_APP_PAYPAL_CLIENT_ID=xxxx \
#     -t <registry>/ecommerce-msa/frontend:1.0.0 -f frontend/Dockerfile frontend

FROM node:20-alpine AS builder
WORKDIR /app

ARG REACT_APP_API_URL
ARG REACT_APP_PAYPAL_CLIENT_ID
# ⚠️ 기본값은 **빈 문자열**이어야 한다. `/` 로 두면 화면들이 쓰는
#    `${process.env.PUBLIC_URL}/assets/images/x.png` 가 `//assets/images/x.png` 가 되는데,
#    이것은 경로가 아니라 **프로토콜 상대 URL** 이다 — 브라우저가 `assets` 를 **호스트
#    이름으로** 해석해 엉뚱한 곳에 요청을 보내고 결제수단 아이콘이 전부 깨진다.
#    (운영 빌드 경로인 ops/19-build-images.sh 는 PUBLIC_URL="" 로 호스트에서 번들을 만들고
#     ops/Dockerfile.frontend-runtime 으로 감싸므로 영향이 없다. 이 파일은 그 밖의 경로용이다.)
ARG PUBLIC_URL=
ARG NODE_ENV=production

COPY package.json package-lock.json* ./
RUN npm ci

COPY . .

RUN printf 'REACT_APP_API_URL=%s\nREACT_APP_PROFILE=%s\nPUBLIC_URL=%s\nREACT_APP_PAYPAL_CLIENT_ID=%s\n' \
      "$REACT_APP_API_URL" "$NODE_ENV" "$PUBLIC_URL" "$REACT_APP_PAYPAL_CLIENT_ID" > .env.production \
    && npm run build

# ---- 런타임 이미지: 정적 파일을 nginx로 서빙 ----
FROM nginx:1.27-alpine
COPY --from=builder /app/dist /usr/share/nginx/html
# nginx.conf 를 **템플릿**으로 넣는다. 공식 엔트리포인트가 기동 시 envsubst 로 치환한다.
# conf.d 로 바로 넣으면 ${GATEWAY_URL} 이 그대로 남아 nginx 가 기동에 실패한다.
COPY nginx.conf /etc/nginx/templates/default.conf.template

# ⚠️ 치환 대상을 제한한다. 필터가 없으면 envsubst 가 $host, $remote_addr 같은
#    **nginx 자체 변수까지 빈 문자열로 치환**해 프록시가 통째로 망가진다.
ENV NGINX_ENVSUBST_FILTER="^(GATEWAY_URL)$"
# 게이트웨이 주소. k8s 에서는 매니페스트가 덮어쓴다.
ENV GATEWAY_URL="http://gateway-server.ecommerce.svc.cluster.local:8000"


RUN addgroup -S spring && adduser -S spring -G spring \
    && chown -R spring:spring /usr/share/nginx/html /var/cache/nginx /var/log/nginx /etc/nginx/conf.d /etc/nginx/templates \
    && touch /var/run/nginx.pid && chown spring:spring /var/run/nginx.pid
USER spring

EXPOSE 8080
