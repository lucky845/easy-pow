package io.github.lucky845.basic.pow;

/**
 * 存储层故障时的行为策略。
 *
 * <p>PoW 是<b>旁路</b>防护组件:存储挂了意味着"防重放与难度追踪失效",
 * 但并不意味着"业务数据会损坏"。两者怎么取舍应该由使用者决定,而不是写死在库里。</p>
 */
public enum PowStorageFailMode {

    /**
     * 严格模式(默认):存储故障时校验返回 {@link PowStatus#STORAGE_ERROR},拒绝放行。
     *
     * <p>安全优先:防重放是安全属性,失效时必须拒绝。代价是故障期间所有提交都过不去。</p>
     */
    CLOSED,

    /**
     * 降级模式:存储故障时切换到降级路径,校验照常放行(通过签名与工作量校验,放弃防重放与存储级过期判断)。
     *
     * <p>可用性优先。若配置了 {@code fallbackStorage}(如 {@link InMemoryPowStorage}),
     * 则在其上继续做防重放,只是不具备跨实例能力;否则完全放弃防重放。</p>
     *
     * <p><b>安全代价</b>:降级期间已通过的 solution 可被重放。仅建议用于"防重放价值低于可用性"的场景。</p>
     */
    OPEN
}
