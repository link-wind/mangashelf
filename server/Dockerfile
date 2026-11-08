# API 镜像：多阶段构建（DESIGN 20.1）。角色由 MANGASHELF_ROLE 决定（同一 jar）。
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY server/pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY server/src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

FROM eclipse-temurin:25-jre-noble
WORKDIR /opt/app
COPY --from=build /build/target/mangashelf-server-0.1.0-SNAPSHOT.jar app.jar
USER 1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
