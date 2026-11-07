package com.mangashelf.ingestion;

import java.nio.file.Path;
import java.util.UUID;

/**
 * 处理任务上下文：固定路径与进度回调，不携带数据库凭据（DESIGN 12.2）。
 * attempt 是本次执行（领取后自增）的编号；workDir 与对象 Key 都用它，保持一致。
 */
public record JobContext(Job job, int attempt, UUID leaseToken, Path workDir, ProgressEmitter progress) {

    @FunctionalInterface
    public interface ProgressEmitter {
        void emit(String stage, String message);
    }
}
