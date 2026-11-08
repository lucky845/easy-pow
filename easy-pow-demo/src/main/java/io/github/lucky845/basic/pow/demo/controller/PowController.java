package io.github.lucky845.basic.pow.demo.controller;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.lucky845.basic.pow.AltchaCodec;
import io.github.lucky845.basic.pow.PowChallengeIssuer;
import io.github.lucky845.basic.pow.PowChallengeVerifier;
import io.github.lucky845.basic.pow.PowProperties;
import io.github.lucky845.basic.pow.PowResult;
import io.github.lucky845.basic.pow.PowStatus;
import io.github.lucky845.basic.pow.PowStorage;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 演示控制器:GET /pow/challenge 下发 altcha challenge;POST /pow/verify 校验 base64(JSON) solution。
 *
 * @author created by lucky845 on 2026-02-06
 */
@RestController
@RequestMapping("/pow")
public class PowController {

    private static final long ISSUE_WINDOW_MS = 60_000L;
    /** 固定窗口上限:本机联调脚本会连刷几十次,给足余量,同时仍能演示"签发必须限流"。 */
    private static final int MAX_ISSUES_PER_WINDOW = 200;

    private final PowChallengeIssuer issuer;
    private final PowChallengeVerifier verifier;
    private final ConcurrentHashMap<String, Window> issueWindows = new ConcurrentHashMap<>();

    private final PowStorage storage;
    private final String failMode;

    public PowController(PowChallengeIssuer issuer, PowChallengeVerifier verifier,
                         PowStorage storage, PowProperties properties) {
        this.storage = storage;
        this.failMode = properties.getStorageFailMode() == null ? null : properties.getStorageFailMode().name();
        this.issuer = issuer;
        this.verifier = verifier;
    }

    /** 当前存储实现与故障策略:页面据此提示「内存 / Redis」,省得用户猜自己看的是哪条链路。 */
    @GetMapping(value = "/storage", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> storage() {
        String name = storage.getClass().getSimpleName();
        return Map.of("storage", name, "failMode", failMode == null ? "" : failMode);
    }

    /** 下发 altcha challenge,字段与官方协议一致。 */
    @GetMapping(value = "/challenge", produces = MediaType.APPLICATION_JSON_VALUE)
    public String challenge(HttpServletRequest request) {
        checkIssueRate(request.getRemoteAddr());
        return AltchaCodec.toJson(issuer.issue());
    }

    /**
     * 校验 solution——表单路径:手动联调面板以 {@code payload} 表单字段提交,
     * altcha 组件随表单提交时字段名为 {@code altcha}。原始 JSON 同样接受。
     */
    @PostMapping(value = "/verify",
            consumes = {MediaType.APPLICATION_FORM_URLENCODED_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public VerifyResponse verify(@RequestParam(value = "payload", required = false) String payload,
                                 @RequestParam(value = "altcha", required = false) String altcha) {
        return doVerify(payload != null && !payload.isBlank() ? payload : altcha);
    }

    /**
     * 校验 solution——JSON 路径:altcha 组件 verifyurl 模式 POST JSON body
     * {@code {"payload": "<base64(JSON)>"}}(组件 dist 源码实证),与表单路径等价。
     */
    @PostMapping(value = "/verify", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public VerifyResponse verifyJson(@RequestBody Map<String, String> body) {
        String payload = body.get("payload");
        String altcha = body.get("altcha");
        return doVerify(payload != null && !payload.isBlank() ? payload : altcha);
    }

    private VerifyResponse doVerify(String solution) {
        if (solution == null || solution.isBlank()) {
            return new VerifyResponse(false, PowStatus.BAD_SOLUTION.name(),
                    "缺少 solution:请以 payload(或 altcha)参数提交 base64(JSON)", solution);
        }
        PowResult result = verifier.verify(solution);
        return new VerifyResponse(result.status() == PowStatus.PASSED, result.status().name(), result.reason(), solution);
    }

    /**
     * ponytail: 单机 demo 用固定窗口 per-IP 限流,仅演示"签发接口必须限流";不清理历史 IP、代理/NAT 下不准确。
     * 生产请上网关限流或 Bucket4j + 共享存储,并保留 easy-pow.max-pending-challenges 作为最后一道容量保护。
     */
    private void checkIssueRate(String clientIp) {
        long now = System.currentTimeMillis();
        if (issueWindows.size() > 4096) {
            issueWindows.entrySet().removeIf(e -> now - e.getValue().startMs >= ISSUE_WINDOW_MS);
        }
        Window window = issueWindows.compute(clientIp, (key, old) ->
                old == null || now - old.startMs >= ISSUE_WINDOW_MS ? new Window(now) : old);
        int count = window.count.updateAndGet(c -> Math.min(c + 1, MAX_ISSUES_PER_WINDOW + 1));
        if (count > MAX_ISSUES_PER_WINDOW) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "challenge 签发过于频繁,请稍后重试");
        }
    }

    private static final class Window {
        private final long startMs;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long startMs) {
            this.startMs = startMs;
        }
    }

    /**
     * 校验结果响应
     *
     * @param success 校验是否通过
     * @param status  结果状态名
     * @param message 结果说明
     * @param payload 回显 solution:altcha v2 组件的 verifyurl 流程必须从响应读回 payload
     *                才会置 VERIFIED(dist 源码 r?.payload&&b(ke,r.payload),否则无限 Verifying)
     */
    public record VerifyResponse(boolean success, String status, String message, String payload) {
    }
}
