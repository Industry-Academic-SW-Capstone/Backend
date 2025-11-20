FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Gradle 파일들을 먼저 복사하여 의존성 캐시
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
RUN chmod +x ./gradlew && ./gradlew dependencies

# 소스 코드를 복사하고 애플리케이션 빌드
COPY . .
RUN ./gradlew build -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 보안을 위해 non-root 유저 생성 및 사용
RUN addgroup --system stockit && adduser --system --ingroup stockit stockit

# 빌드 스테이지에서 JAR 파일만 복사 (구체적인 파일명 사용)
COPY --from=build /app/build/libs/stockIt-*.jar app.jar

# 파일 소유권 변경
RUN chown stockit:stockit app.jar
USER stockit

EXPOSE 8080

# 타임존 설정 (Asia/Seoul)
ENV TZ=Asia/Seoul
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Seoul /etc/localtime && \
    echo "Asia/Seoul" > /etc/timezone

# JVM 옵션 추가 (컨테이너 환경 최적화 및 타임존 설정)
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+UseStringDeduplication -Duser.timezone=Asia/Seoul"

# 헬스체크 추가
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]