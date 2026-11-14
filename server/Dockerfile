# API 镜像：Maven 构建层 + temurin 25 运行层。
# 也可在 CI 中先构建 jar 后复用运行层；当前默认允许服务器直接从源码构建。
FROM maven:3-eclipse-temurin-25 AS build
# maven 3.9 镜像自带 ENV MAVEN_CONFIG=/root/.m2；mvnw 3.3.x 会原样
# 拼进 java 命令行，Maven 将其当成生命周期参数而失败。构建层清空。
ENV MAVEN_CONFIG=
WORKDIR /src
COPY server/pom.xml server/mvnw ./
COPY server/.mvn .mvn
COPY server/src src
RUN chmod +x mvnw && MAVEN_CONFIG= ./mvnw -q -DskipTests package

FROM eclipse-temurin:25-jre-noble
WORKDIR /opt/app
COPY processing/requirements.txt /tmp/requirements.txt
RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-venv \
 && python3 -m venv /opt/browser-venv \
 && /opt/browser-venv/bin/pip install --no-cache-dir -r /tmp/requirements.txt \
 && PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright /opt/browser-venv/bin/python -m playwright install --with-deps chromium \
 && mkdir -p /opt/data/jobs /opt/data/bilibili-http-cache /opt/browser-home \
 && chown -R 1000:1000 /opt/data /opt/browser-home \
 && rm -rf /var/lib/apt/lists/*
COPY processing /opt/processing
COPY --from=build /src/target/mangashelf-server-0.1.0-SNAPSHOT.jar app.jar
ENV MANGASHELF_PYTHON=/opt/browser-venv/bin/python \
    MANGASHELF_PROCESSING_ROOT=/opt \
    MANGASHELF_WORK_ROOT=/opt/data/jobs \
    PLAYWRIGHT_BROWSERS_PATH=/opt/ms-playwright \
    HOME=/opt/browser-home
USER 1000
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
