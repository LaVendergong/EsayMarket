# 多模块 Maven 项目统一构建：用 MODULE 参数决定产出哪个可执行 jar。
# 构建上下文必须是 backend/（父 POM 需要全部子模块在场才能 reactor 构建）。
#
#   docker build -f backend/Dockerfile --build-arg MODULE=mall-admin ./backend
#
# 注意：容器里的构建是「先 COPY 全部源码再 mvn package」。之所以不做完整的分层缓存优化，
# 是因为本项目只有 4 个模块、整体构建约 25 秒，简单比"聪明"更值。

# ---------------------------------------------------------------- 构建阶段
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY . .
ARG MODULE=mall-admin
RUN mvn -B -DskipTests clean package \
    && cp ${MODULE}/target/${MODULE}-*.jar /app.jar

# ---------------------------------------------------------------- 运行阶段
FROM eclipse-temurin:17-jre
WORKDIR /app

# 用非 root 跑
RUN useradd -r -u 1001 -m app
COPY --from=build /app.jar /app/app.jar
RUN chown -R app:app /app
USER app

# JAVA_OPTS 可用来调堆大小，例如 JAVA_OPTS="-Xmx256m"
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
