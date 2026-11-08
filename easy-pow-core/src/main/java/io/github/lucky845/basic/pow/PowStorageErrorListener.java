package io.github.lucky845.basic.pow;

/**
 * 存储故障旁路回调。
 *
 * <p>校验路径的存储故障有 {@link PowStatus#STORAGE_ERROR} 可观测,签发路径没有——
 * 调用方拿到的是异常,而不是一次"校验结论"。实现本接口即可让两类故障共用同一计数口径。</p>
 *
 * <p>实现必须快速返回且不得抛异常:它跑在签发关键路径上,阻塞会直接拖慢签发,
 * 抛出的 {@link RuntimeException} 会被忽略(观测是旁路,不允许影响业务结果)。</p>
 *
 * @author created by lucky845
 */
@FunctionalInterface
public interface PowStorageErrorListener {

    /**
     * @param operation 发生故障的操作,如 {@code "issue"}
     * @param cause     存储抛出的原始异常(由调用方随后重新抛出,监听器不得吞掉之后改变流程)
     */
    void onStorageError(String operation, RuntimeException cause);
}
