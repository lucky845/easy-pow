# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/),变更分类参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

发布到 Maven Central 的组件:`easy-pow-core`、`easy-pow-spring-boot-starter`(`easy-pow-demo` 不发布)。
坐标与配置项说明见 [README](README.md)。

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
