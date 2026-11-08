package io.github.lucky845.basic.pow;

/** 校验器相关测试共用的 payload 构造。 */
final class VerifierTestSupport {

    private VerifierTestSupport() {
    }

    /** 解出满足 challenge 的 number。 */
    static long solve(PowChallenge challenge) {
        long max = challenge.maxnumber();
        for (long n = 0; n <= max; n++) {
            if (Hashes.equalsHex(Hashes.shaHex(challenge.algorithm(), challenge.salt() + n), challenge.challenge())) {
                return n;
            }
        }
        throw new IllegalStateException("解不出 challenge");
    }

    /** 解出 number 并拼成 solution JSON,字段名与 {@link AltchaCodec.Solution} 及 altcha 协议一致。 */
    static String validSolution(PowChallenge challenge) {
        return "{\"algorithm\":\"" + challenge.algorithm() + "\",\"challenge\":\"" + challenge.challenge()
                + "\",\"number\":" + solve(challenge) + ",\"salt\":\"" + challenge.salt()
                + "\",\"signature\":\"" + challenge.signature() + "\",\"took\":42}";
    }
}
