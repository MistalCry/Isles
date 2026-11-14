# syntax=docker/dockerfile:1
# 群岛后端镜像：Maven 构建 → JRE 运行
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY backend/pom.xml .
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
ENV TZ=UTC \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r isles && useradd -r -g isles isles && mkdir -p /app/uploads && chown -R isles:isles /app
COPY --from=build /workspace/target/isles-backend-*.jar /app/app.jar
USER isles
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
