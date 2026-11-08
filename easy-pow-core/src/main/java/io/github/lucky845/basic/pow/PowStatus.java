package io.github.lucky845.basic.pow;

/** 校验结果状态。 */
public enum PowStatus {
    /** 校验通过。 */ PASSED,
    /** 挑战已被使用(防重放命中)。 */ REUSED,
    /** 挑战已过期或无签发记录。 */ EXPIRED,
    /** HMAC 签名不匹配(secret 不一致或字段被篡改)。 */ BAD_SIGNATURE,
    /** solution 无法解析、算法不支持或 challenge 与 salt+number 不匹配。 */ BAD_SOLUTION,
    /** number 超出签发时的 maxnumber。 */ DIFFICULTY_EXCEEDED,
    /**
     * 存储层故障(Redis 不可达、超时、序列化失败等)。
     *
     * <p>与 {@link #EXPIRED} 严格区分:后者是<b>确定</b>的客户端问题,前者是<b>服务端</b>基础设施问题。
     * 归错类别会让面板显示"大量挑战过期"——比直接 500 更难排查。</p>
     */
    STORAGE_ERROR
}
