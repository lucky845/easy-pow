# easy-pow demo — 一条命令起完整联调环境
#
#   docker compose up --build
#   浏览器打开 http://localhost:8080/
#
# 两阶段构建:第一阶段在容器内跑 Maven(不依赖本机 JDK);第二阶段只留 JRE 和 fat jar,
# 镜像里没有源码、没有 Maven、没有 .m2。

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# 先只拷 pom,把依赖层单独缓存:pom 没变时重建不重下依赖。
# demo 通过 reactor 依赖 core/starter,所以三个 pom 必须一起拷。
COPY pom.xml ./
COPY easy-pow-core/pom.xml ./easy-pow-core/
COPY easy-pow-spring-boot-starter/pom.xml ./easy-pow-spring-boot-starter/
COPY easy-pow-demo/pom.xml ./easy-pow-demo/
RUN mvn -B -q -pl easy-pow-demo -am dependency:go-offline || true

COPY easy-pow-core/src ./easy-pow-core/src
COPY easy-pow-spring-boot-starter/src ./easy-pow-spring-boot-starter/src
COPY easy-pow-demo/src ./easy-pow-demo/src
# 单测在 CI 跑;镜像只需要能跑起来的 jar,跳测试让构建快一档。
RUN mvn -B -q -pl easy-pow-demo -am package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 非 root 运行:容器逃逸的收益面小一点,代价只是一行。
RUN addgroup -S pow && adduser -S pow -G pow
COPY --from=build /workspace/easy-pow-demo/target/easy-pow-demo-*.jar app.jar
USER pow

EXPOSE 8080

# 默认内存存储;接 Redis 时由 compose 覆盖 SPRING_PROFILES_ACTIVE=redis。
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]