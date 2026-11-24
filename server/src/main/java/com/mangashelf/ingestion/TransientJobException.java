package com.mangashelf.ingestion;

/**
 * 显式白名单瞬时错误（harden-reliable-jobs-and-fault-injection）：
 * 仅用于网络超时、上游 5xx、对象存储瞬时故障等可重试场景。
 * 权限、格式、参数错误与未声明异常严禁包装为此类，必须直接失败（AGENTS.md）。
 */
public class TransientJobException extends RuntimeException {

    private final String errorCode;

    public TransientJobException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TransientJobException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
