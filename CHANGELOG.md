# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/),变更分类参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

发布到 Maven Central 的组件:`easy-pow-core`、`easy-pow-spring-boot-starter`(`easy-pow-demo` 不发布)。
坐标与配置项说明见 [README](README.md)。

## [Unreleased]

### 新增

- **Redis key 前缀可配置**(`easy-pow.redis-key-prefix`,默认 `easy-pow:challenge:`):此前前缀写死在
  `RedisPowStorage.DEFAULT_PREFIX`,同一个 Redis 被多个应用或 prod/staging 共用时只能靠人品避让。
  消费标记前缀由挑战前缀派生(`challenge` 段替换为 `consumed`),`DEFAULT_PREFIX` 仍派生出历史值
  `easy-pow:consumed:`,**升级不迁移数据**。
  `RedisPowStorage` 新增 `(redis, clock, prefix)` 构造与 `prefix()`;空白前缀快速失败,
  避免写出所有人共享的空 key。前缀改变会使在途挑战失效(见 README 配置参考)。

### 修复

- **负数 number 未判非法**:枚举范围是 `[0, maxnumber]`,但校验链只拦了上界(`number > maxnumber`
  → `DIFFICULTY_EXCEEDED`),负数没有任何一支拦。虽然 `SHA256(salt + (-5))` 几乎不可能等于 challenge,
  但这意味着"合法解"的定义里缺少下界——现在明确判 `BAD_SOLUTION`。
  新增两条用例:负数 payload 被拒;以及伪造 challenge 让负数摘要成立时仍判 `BAD_SOLUTION`,
  而不是落进 `DIFFICULTY_EXCEEDED`。

### 文档

- README 监控指标节说明 `pow.verify.rejected` 的 `reason` tag 按 `PowStatus` **自动分档**:
  新增状态即多一档,升级后无需改查询。

## [0.1.6] - 2026-11-08

### 新增

- **存储熔断可观测性**:新增 `pow.storage.tripped.for.ms`(熔断持续毫秒数)与 `pow.storage.size`
  (内存存储条目数)。原来只有 0/1 的 `pow.storage.tripped`,看不出"已经降级 5 分钟没人管",
  也看不出内存存储是否逼近 `max-pending-challenges`。
- **`PowStorageErrorListener`**:存储故障旁路接口 `onStorageError(operation, cause)`,
  由 `PoWChallengeIssuer` / `PowChallengeVerifier` 在 `save` / `find` / `consume` 失败时回调。
  签发路径没有 `PowStatus` 可复用,此前这类故障在 `pow.storage.errors` 上完全不可见。
- `FailModePowStorage` 新增 `trippedForMs()`;主存储故障时打一条 WARN(仅在状态翻转时打,不刷日志)。
- `InMemoryPowStorage` 新增 `size()` / `maxPendingChallenges()`。

### 修复

- **Redis 存储实际未生效**:`RedisPowAutoConfiguration` 的 `@ConditionalOnMissingBean(PowStorage.class)`
  写在 `@Bean` 方法上,与类级 `@AutoConfiguration(before = PowAutoConfiguration.class)` 叠加后,
  两个自动配置互相把对方判成"已存在",最终注入的是内存存储——`/pow/storage` 显示包装器,
  但请求走的是 `InMemoryPowStorage`。条件改为类级后 Redis 实现正常生效。
- **`FailModePowStorage.save` 绕过故障处理**:`save` 直接调 `primary.save(...)` 而没有 try/catch,
  Redis 故障时熔断状态永远不置位(`pow.storage.tripped` 恒为 0,日志里也没有任何告警)。
  现在失败必经 `markTripped`,签发本身仍不降级、异常照旧抛给调用方。
- **`fail-mode: CLOSED` 熔断后会误判成"挑战过期"**:`find` 的短路条件由 `isTripped()` 收窄为
  `isTripped() && mode == OPEN`,CLOSED 下继续打到主存储并由校验器转成 `STORAGE_ERROR`。

### 兼容性

- 无破坏性变更。新增构造器与监听器接口均为追加,旧的 `PowChallengeIssuer` / `PowChallengeVerifier`
  构造器签名保持不变。
- `easy-pow-core` 新增 `org.slf4j:slf4j-api`(`provided`),不传递到使用方。

### 测试

- core 62 + starter 38 = 100 用例全绿,新增覆盖:签发/校验的存储故障上报、
  旁路监听器自身抛异常不改变结论、`save` 失败计入熔断、`CLOSED` 不再短路。

## [0.1.5] - 2026-11-08

### 新增

- **demo 演示 Redis 存储**:新增 `application-redis.yml`,`--spring.profiles.active=redis` 即切到 `RedisPowStorage`,
  用来验证 starter「有 `StringRedisTemplate` 就自动装配 Redis 存储」这条链路(此前只有单元测试,没有端到端路径)。
- **`GET /pow/storage`**:返回当前存储实现与故障策略,如 `{"storage":"RedisPowStorage (经 FailModePowStorage 包装)","failMode":"CLOSED"}`;
  页面顶部同步显示,避免看到包装层名字却不知道底层是内存还是 Redis。为此 `FailModePowStorage` 新增 `primary()`。
- **`tools/pow-bench.mjs`**:零依赖 Node 压测脚本,分别统计签发与校验延迟;README 新增「压测」节含参考读数。
- demo 签发限流可用环境变量 `ISSUE_RATE_DISABLED=true` 关闭(压测需要),关闭时 429 文案会提示。
- `FailModePowStorage.primary()`:暴露被包装的主存储,便于诊断与自定义装配。

### 修复

- **内存存储清理退化为 O(n²)**:超过 `COMPACT_THRESHOLD` 后,无容量上限时每次 `save` 都触发一次全表扫描。
  现按"清理多少条才降回阈值"记账(`compactionDebt`),扫描完一次后不再每写必扫。
- `PowChallengeVerifier` 的 number 上界判定补注释明确:上界取自**服务端存储的记录**,
  `solution.maxnumber` 是客户端自填的不可信字段;新增用例锁死"客户端谎报巨大 maxnumber 也无法放宽上界"。

### 兼容性

- 无破坏性变更。新增的 `primary()` 与 `tools/pow-bench.mjs` 均为追加,`/pow/storage` 只存在于 demo。

## [0.1.4] - 2026-11-08

### 新增

#### 难度自适应:观测客户端算力

- 新增 `AdaptiveDifficulty.ewmaHashesPerMs()` 与指标 `pow.adaptive.hashes.per.ms`:成本占比
  `下发难度 / 实际耗时`(次/毫秒)的 EWMA。此前只暴露"耗时"与"难度"两个结果,
  看不出难度该不该动;现在能区分"客户端变慢"与"难度没跟上"。
- 签发记录从 `签发时刻` 扩为 `签发时刻 + 当次下发难度`,成本占比的分子与分母取自同一次签发。
- 新增下界 `MIN_HASHES_PER_MS`:耗时快到时钟回拨或样本伪造才可能达到的速度下,该样本直接丢弃。

### 修复

- **控制律必须是无状态映射**:`currentMaxnumber()` 曾尝试写成"乘上一次下发值"的积分形式,
  等于把输出接回输入,给控制器加了一阶极点;实测 200 轮后由 20000 发散到 31619(真实算力固定的场景)。
  现已明确为 `current = clamp(base × target / EWMA, min, max)`,并新增 `readsArePure` 用例锁死"读不推进状态"。

#### 已知偏差(未修复,写入 README)

- 稳态难度停在 `√(base × target × 算力)` 而非 `target × 算力`,系统性偏高约 1.58 倍。
  根因是 EWMA 稳态值等于当次实测值、控制律又把 `base` 当锚,`D` 与 `1/D` 相乘形成二次不动点。
  修正需要改成显式算力估计的开环反解,代价是算力突变时超调;当前选择保留闭环并披露偏差,
  用例 `convergesToTargetForKnownClient` 断言的是真实不动点(31623)而不是理想值。
#### 存储故障可配置:`easy-pow.storage-fail-mode`

- 新增枚举 `PowStorageFailMode`:`CLOSED`(默认)拒绝放行、`OPEN` 降级放行。此前 Redis 抖动时 `find()` 的异常原样上抛,校验接口变 500,既不 fail-closed 也不 fail-open,且不可配置。
- 新增 `FailModePowStorage` 装饰器:自动配置的 Redis 存储被它包一层。故障时 `CLOSED` 重抛、由校验器转成 `STORAGE_ERROR`;`OPEN` 熔断 `storage-cooldown-ms`(默认 30s)并走兜底存储(可用 `fallback-storage` 指定 Bean,缺省退化到进程内)。`save()` **不降级**——签发失败让调用方拿到异常,比静默写进一个即将消失的存储更好。
- 新增配置项 `storage-fail-mode` / `storage-cooldown-ms` / `fallback-storage`。

#### 拒绝可观测

- `PowStatus` 新增 `STORAGE_ERROR`:存储故障必须与 `EXPIRED` 分开。若把故障吞成 null,面板会显示"大量挑战过期",比 500 更难查。
- `PowDifficultyListener` 新增 `default void onRejected(String challengeKey, PowStatus status)`,默认转调 `onVerified` 以保持既有实现行为。
- 新增指标 `pow.verify.rejected{reason}`(按 `PowStatus` 小写名分档)、`pow.storage.errors`、`pow.storage.tripped`(熔断状态)。
- 指标 `pow.solve.ms.ewma`(Gauge):解题耗时 EWMA 毫秒。只暴露 `pow.maxnumber`(结果)无法判断自适应控制器是否在有效工作,现在原因与结果一并可见;未挂载自适应时为 `-1`(表示"不适用",不是 0ms)。

### 修复

- **被攻击时指标一片绿**:校验器五个拒绝分支都直接 `return PowResult.fail(...)`,而监听器只在 PASSED 回调,于是攻防演练里看到的永远是"通过 0"。现在所有失败收敛到私有 `fail(status, reason, challengeKey)` 统一出口,先回调 `onRejected` 再返回;监听器自身异常仍被吞掉,不影响主结果。
- `PoWMetrics` 必须覆盖 `onRejected`:父接口的默认实现会把每次拒绝记成一次通过——那个默认值是为了兼容,不是为了正确。
- **`pow.challenge.issued` / `verified` / `maxnumber` 在真实应用里从未注册**:详见下方同名条目(先于本轮完成)。
- `PoWMetrics` 在 `registry` 为 null 时不再 NPE,退化为空实现(启动不因观测缺失而失败)。

### 兼容性

- 默认 `storage-fail-mode: CLOSED` 下,Redis 故障的 HTTP 表现由 500 变为一次正常的 `PowResult.fail(STORAGE_ERROR)`(HTTP 层通常 400/422,由控制器决定),拒绝原因字符串以"存储查询失败"/"存储消费失败"开头。依赖原先异常类型的调用方需要调整。
- 自动配置的 `PowStorage` Bean 现在实际类型是 `FailModePowStorage`(装饰器)。按类型注入不受影响,按具体类型 `instanceof RedisPowStorage` 或强制转型的代码需要调整。
- 自定义 `PowStorage` Bean 不会被包装,行为不变。

### 测试

- 新增 12 例:FailModePowStorage 6 例(CLOSED 上抛且不熔断 / OPEN 无兜底 / 熔断期内不打 Redis / 兜底防重放 / 恢复解除 / save 不降级)、VerifierStorageError 3 例(find 与 tryConsume 故障都记 `STORAGE_ERROR` 且不计通过 / 每个拒绝分支都回调)、指标拒绝 3 例。合计 core 53 + starter 33 = 86。

## [0.1.3] - 2026-11-08

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

### 兼容性

- 行为变更(向下兼容):监听器抛出的异常不再传播。此前依赖该异常做流程控制的调用方需自行处理——但该行为本就与"观测旁路"的设计意图相反。

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