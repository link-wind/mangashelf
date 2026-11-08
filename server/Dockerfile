# API 镜像：本机构建 jar + temurin 25 运行层。
# 前置：server/ 下 ./mvnw package -DskipTests（CI/CD 中由流水线执行）。
FROM eclipse-temurin:25-jre-noble
WORKDIR /opt/app
COPY server/target/mangashelf-server-0.1.0-SNAPSHOT.jar app.jar
USER 1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
