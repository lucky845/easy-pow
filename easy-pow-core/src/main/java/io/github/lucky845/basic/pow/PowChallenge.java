package io.github.lucky845.basic.pow;

/**
 * 已签发的 PoW 挑战(值对象),字段与 altcha 官方协议一致。
 *
 * @param algorithm 算法名,当前固定 "SHA-256"
 * @param challenge sha256(salt + number) 的 hex
 * @param maxnumber 难度上限,number ∈ [0, maxnumber]
 * @param salt      随机盐(16 字节 = 128 bit 熵)hex
 * @param signature HMAC-SHA256(secret, challenge) hex
 * @param expiresAt 过期时间(epoch 毫秒);仅供服务端使用,不序列化给前端
 */
public record PowChallenge(String algorithm, String challenge, long maxnumber,
                           String salt, String signature, long expiresAt) {
}
