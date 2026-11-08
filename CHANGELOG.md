# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/),变更分类参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

发布到 Maven Central 的组件:`easy-pow-core`、`easy-pow-spring-boot-starter`(`easy-pow-demo` 不发布)。
坐标与配置项说明见 [README](README.md)。

## [Unreleased]

### 新增

- Micrometer 指标(可选依赖):starter 检测到 `MeterRegistry` 时自动注册 `PoWMetrics`,暴露 `pow.challenge.issued`/`pow.challenge.verified`(Counter)与 `pow.maxnumber`(Gauge);未引入 micrometer 时安静跳过,不产生额外依赖。
- `PoWListenerComposite`:多个 `PowDifficultyListener` 合并为一条链,使"难度自适应"与"指标/自定义观测"能同时挂载。

- 内存存储容量保护:`easy-pow.max-pending-challenges`(默认 0=不限)限制待处理挑战数,超限抛 `IllegalStateException`;starter 自动配置透传,demo 增加 per-IP 固定窗口限流示例,README 安全章节补网关/Bucket4j 限流建议。

### 测试

- 新增 5 例:容量上限拒绝/同 key 可覆盖/过期项不占额度/签发器满拒/自动配置透传。
- 新增 5 例:关闭自适应时自定义监听器仍触发/监听器抛异常不影响签发/Micrometer 在场时注册指标并计数/无 micrometer 时安静跳过(`FilteredClassLoader`)/难度上限 Gauge 回落配置值。
- 新增 2 例:消费后保留窗口内 `find` 仍可见、窗口结束后不可见;另补 `PowConfig` 难度上界正/负向断言。

### 修复

- 签发器与 `PowConfig` 原先只拦 `maxnumber == Long.MAX_VALUE`;现在统一为 `>= Long.MAX_VALUE` 一律拒绝,与内部 `maxnumber + 1` 的闭区间上界保持一致。`Long.MAX_VALUE - 1` 加一后仍是正数,仍可合法使用。(上一条变更说明曾把 `Long.MAX_VALUE - 1` 写成会溢出,属笔误,已在 `PowConfigTest` 中用正向断言固定真实行为。)
- `PowChallenge` / `PowChallengeIssuer` 的 Javadoc 仍写着"固定 SHA-256",与 0.1.2 起的多算法行为不符,已更正。
- **监听器异常不再反噬主流程**:`PowChallengeIssuer` 签发成功后调用 `listener.onIssued`、`PowChallengeVerifier` 校验通过后调用 `listener.onVerified`,此前监听器抛出的任何 `RuntimeException` 都会直接冒泡——挑战已落存储却让调用方拿到异常,或已通过的校验被翻成失败。现在两处均为旁路捕获,观测故障只影响观测。
- **关闭自适应时指标/自定义监听器曾完全失效**:`PowAutoConfiguration` 只在 `adaptive.enabled=true` 时把控制器作为监听器挂载,其余监听器无处安放。现在二者合并成链,观测不再依赖自适应开关。
- **内存存储消费语义与 Redis 对齐**:`InMemoryPowStorage.tryConsume` 原先直接 `compareAndSet` 后就地修改条目,消费标记没有独立生命周期;现在记录消费时刻并按 `ttlMs` 保留,窗口内 `find` 仍返回记录,重放稳定判为 `REUSED` 而不是 `EXPIRED`。`RedisPowStorage` 类注释一直声称"与内存实现语义一致",此前实际相反。

### 工程

- `easy-pow-spring-boot-starter` 的 surefire 增加 `-javaagent:mockito-core`,消除 Mockito inline mock maker 的自附加告警(JDK 未来版本会禁止自附加)。只在引入 Mockito 的模块启用——core 无该依赖,全局启用会让 fork JVM 找不到 agent jar 直接崩溃。

## [0.1.2] - 2026-11-07

### 新增

- 多算法支持:`easy-pow.algorithm` 可选 `SHA-256` / `SHA-384` / `SHA-512`,默认 `SHA-256`。摘要与 HMAC 签名都按该算法执行,签发的挑战自带 `algorithm` 字段供前端选择实现。
- 真机 Redis 端到端冒烟测试(`RedisPowStorageRealRedisTest`,embedded-redis 自动拉起 `redis-server`)。

### 安全

- 校验拒绝算法降级:`algorithm` 字段不参与签名,因此除了"必须受支持"之外,还要求与当前配置**完全一致**;否则攻击者可自选弱算法重算 challenge 绕过难度。

### 变更

- `PowConfig` 增加 `algorithm` 字段(构造器由三参变四参),`PowChallenge.algorithm` 不再是常量 `SHA-256`。直接构造 `PowConfig` 的调用方需补该参数;用 Spring Boot starter 的只需配 `easy-pow.algorithm`。
- 切换算法会让**在途挑战立即失效**(校验按当前配置复算),建议在低峰期切换。

### 修复

- 发布链:`easy-pow-demo` 在 release profile 显式 `skipPublishing`,不再空跑 `central-publishing` 步骤。

### 已知限制

- 不提供 SHA3 系列:altcha@2 前端 dist 只实现 SHA-256 / SHA-384 / SHA-512,后端单方面支持会导致前端无法解题。

## [0.1.1] - 2026-11-07

### 变更

- 挑战消费语义统一,重放可观测(`PowStorage` / `RedisPowStorage` 行为对齐)。

### 修复

- `central-publishing-maven-plugin` 0.7.0 → 0.12.0。0.7.0 在 Portal 响应新增 `warnings` 字段后抛 `UnrecognizedPropertyException`,导致 0.1.0 的发布步骤失败。

### 工程

- 新增 CI:PR 与 push 触发 `mvn -B verify`(JDK 21)。

## [0.1.0] - 2026-11-06

### 新增

- 首次发布:`easy-pow-core`(挑战签发、校验、内存存储)与 `easy-pow-spring-boot-starter`(自动配置,含 Redis 存储与难度自适应)。
