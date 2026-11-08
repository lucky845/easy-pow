package io.github.lucky845.basic.pow;

/**
 * solution 校验器。校验链(任一环节失败即短路返回对应 {@link PowResult}):
 * 解析 → 算法名(受支持且与配置一致,拒降级)→ HMAC 签名(常量时间)→ algorithm(salt+number)(常量时间)
 * → number ≤ maxnumber → 未过期 → 存储原子消费(防重放)。
 */
public final class PowChallengeVerifier {

    private final PowConfig config;
    private final PowStorage storage;
    /** 校验事件监听(难度自适应观测点);可为 null。 */
    private final PowDifficultyListener listener;

    public PowChallengeVerifier(PowConfig config) {
        this(config, new InMemoryPowStorage());
    }

    /** 注入自定义存储;必须与 {@link PowChallengeIssuer} 使用同一实例(或同一共享存储)。 */
    public PowChallengeVerifier(PowConfig config, PowStorage storage) {
        this(config, storage, null);
    }

    /** 挂载校验事件监听:仅校验通过时回调 {@code listener.onVerified(challenge)};可为 null。 */
    public PowChallengeVerifier(PowConfig config, PowStorage storage, PowDifficultyListener listener) {
        this.config = config;
        this.storage = storage;
        this.listener = listener;
    }

    /**
     * @param solutionPayload 前端提交的 solution:"base64(JSON)" 或原始 JSON
     * @return 校验结果,永不返回 null;失败原因见 {@link PowResult#reason()}
     */
    public PowResult verify(String solutionPayload) {
        AltchaCodec.Solution solution = AltchaCodec.parseSolution(solutionPayload);
        if (solution == null) {
            return fail(PowStatus.BAD_SOLUTION,
                    "solution 解析失败:须为 base64(JSON) 或扁平 JSON,含 algorithm/challenge/number/salt/signature", null);
        }
        if (!Hashes.isSupported(solution.algorithm())) {
            return fail(PowStatus.BAD_SOLUTION, "不支持的算法: " + solution.algorithm(), solution.challenge());
        }
        // 算法名不参与签名,必须在服务端配置层拒绝降级(否则攻击者可自选弱算法重算 challenge)
        if (!config.algorithm().equals(solution.algorithm())) {
            return fail(PowStatus.BAD_SOLUTION,
                    "算法与当前配置不一致(期望 " + config.algorithm() + "): " + solution.algorithm(),
                    solution.challenge());
        }
        if (!Hashes.equalsHex(Hashes.hmacHex(config.algorithm(), config.secret(), solution.challenge()),
                solution.signature())) {
            return fail(PowStatus.BAD_SIGNATURE, "HMAC 签名不匹配:secret 不一致或字段被篡改", solution.challenge());
        }
        if (!Hashes.equalsHex(Hashes.shaHex(config.algorithm(), solution.salt() + solution.number()),
                solution.challenge())) {
            return fail(PowStatus.BAD_SOLUTION, "摘要(salt+number)与 challenge 不匹配", solution.challenge());
        }
        PowStorage.ChallengeRecord record;
        try {
            record = storage.find(solution.challenge());
        } catch (RuntimeException e) {
            return fail(PowStatus.STORAGE_ERROR,
                    "存储查询失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(), solution.challenge());
        }
        if (record == null) {
            return fail(PowStatus.EXPIRED,
                    "challenge 无签发记录:未签发、已过期被清理,或签发/校验未使用同一存储", solution.challenge());
        }
        if (solution.number() > record.maxnumber()) {
            return fail(PowStatus.DIFFICULTY_EXCEEDED,
                    "number " + solution.number() + " 超过难度上限 " + record.maxnumber(), solution.challenge());
        }
        if (record.expiresAtMs() <= System.currentTimeMillis()) {
            return fail(PowStatus.EXPIRED, "挑战已过期", solution.challenge());
        }
        boolean consumed;
        try {
            consumed = storage.tryConsume(solution.challenge(), config.defaultTtlMs());
        } catch (RuntimeException e) {
            // 消费失败绝不能 fail-open:放行等于关闭防重放
            return fail(PowStatus.STORAGE_ERROR,
                    "存储消费失败: " + e.getClass().getSimpleName() + ": " + e.getMessage(), solution.challenge());
        }
        if (!consumed) {
            return fail(PowStatus.REUSED, "挑战已被使用,疑似重放", solution.challenge());
        }
        if (listener != null) {
            try {
                listener.onVerified(solution.challenge());
            } catch (RuntimeException ignored) {
                // 观测是旁路:监听器故障不得把已通过的校验翻成失败,也不得掩盖主结果
            }
        }
        return PowResult.pass(solution.challenge());
    }

    /**
     * 统一的失败出口:先通知监听器再返回,保证<b>每个</b>拒绝分支都有观测。
     *
     * <p>此前五个拒绝分支都直接 {@code return},监听器只在 PASSED 回调,
     * 于是被攻击时指标一片绿。监听器故障同样不得掩盖主结果。</p>
     */
    private PowResult fail(PowStatus status, String reason, String challengeKey) {
        if (listener != null) {
            try {
                listener.onRejected(challengeKey, status);
            } catch (RuntimeException ignored) {
                // 观测是旁路
            }
        }
        return PowResult.fail(status, reason, challengeKey);
    }
}
