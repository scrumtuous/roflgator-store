# Multi-stage build: compile with Maven, then run on a slim JRE image.
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY src ./src

# Build an executable Spring Boot jar.
RUN mvn -DskipTests clean package

FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /workspace/target/simple-kafka-store-1.0.0.jar /app/app.jar

# App can run on 8080 (default) or 5000 (aws profile).
EXPOSE 8080 5000

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
