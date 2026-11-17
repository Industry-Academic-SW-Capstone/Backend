# 런타임 베이스. 기본은 JRE 이고 운영 배포는 이 값을 그대로 쓴다.
# 프로파일링 회차만 JDK 로 덮어쓴다(jcmd·jfr 이 JRE 에는 없다) — docker-compose.profile.yml 참고.
#
# ★ FROM 에서 쓰는 ARG 는 첫 FROM 앞에 선언해야 한다. 빌드 스테이지 안에서 선언하면
#   그 스테이지에만 유효해서 뒤쪽 FROM 에서는 빈 값이 된다.
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-alpine

# 빌드 스테이지는 빌드하는 기계의 플랫폼으로 돈다. jar 는 플랫폼과 무관해서, Mac(arm64)에서 서버용(amd64) 이미지를
# 만들 때 Gradle 이 에뮬레이션으로 느리게 도는 것을 피한다. 마지막 스테이지만 대상 플랫폼이다.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Gradle 파일들을 먼저 복사하여 의존성 캐시 (모듈마다 build.gradle 이 있다)
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
COPY contract/build.gradle contract/
COPY common/build.gradle common/
COPY market/build.gradle market/
COPY exchange/build.gradle exchange/
COPY broker/build.gradle broker/
COPY extra/build.gradle extra/
COPY app/build.gradle app/
RUN chmod +x ./gradlew && ./gradlew :app:dependencies

# 소스 코드를 복사하고 실행 jar 를 모두 만든다(app·market·exchange·broker·extra).
# 이 단계에는 MODULE 을 쓰지 않는다 — 서비스 이미지 넷과 app 이미지가 이 단계를 캐시로 함께 쓴다.
COPY . .
RUN ./gradlew bootJar

FROM ${RUNTIME_IMAGE}
WORKDIR /app

# 어느 실행 jar 를 담을지. app(넷을 한 프로세스로, 기본) 또는 market·exchange·broker·extra.
ARG MODULE=app

# 보안을 위해 non-root 유저 생성 및 사용
RUN addgroup --system stockit && adduser --system --ingroup stockit stockit

# 타임존 설정 (Asia/Seoul) - root 권한 필요하므로 USER 전에 실행
ENV TZ=Asia/Seoul
RUN apk add --no-cache tzdata && \
  cp /usr/share/zoneinfo/Asia/Seoul /etc/localtime && \
  echo "Asia/Seoul" > /etc/timezone

# 빌드 스테이지에서 JAR 파일만 복사 (구체적인 파일명 사용)
COPY --from=build /app/${MODULE}/build/libs/stockit-${MODULE}.jar app.jar

# 파일 소유권 변경
RUN chown stockit:stockit app.jar
USER stockit

EXPOSE 8080

# JVM 옵션 추가 (컨테이너 환경 최적화 및 타임존 설정)
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+UseStringDeduplication -Duser.timezone=Asia/Seoul"

# 헬스체크 추가
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]