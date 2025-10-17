FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY . .

RUN chmod +x ./gradlew

RUN ./gradlew clean build -x test

COPY src/main/resources/application*.yml /app/config/

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "build/libs/stockIt-0.0.1-SNAPSHOT.jar", "--spring.config.additional-location=optional:file:/app/config/"]
