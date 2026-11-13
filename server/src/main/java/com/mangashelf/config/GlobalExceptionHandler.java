package com.mangashelf.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 统一错误结构：code / message / request_id / details，不返回栈与内部信息（DESIGN 16.2）。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ApiError(String code, String message, String requestId, Map<String, Object> details) {
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException e) {
        return ResponseEntity.status(e.getStatus())
                .body(new ApiError(e.getCode(), e.getMessage(), currentRequestId(), e.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException e) {
        Map<String, Object> details = new HashMap<>();
        if (e.getBindingResult().hasErrors()) {
            var fe = e.getBindingResult().getFieldErrors().getFirst();
            details.put("field", fe.getField());
            details.put("reason", fe.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_ARGUMENT", "请求参数不合法。", currentRequestId(), details));
    }

    /** 缺少必填查询参数等请求绑定失败返回 400，不落入通用 500。 */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(
            org.springframework.web.bind.MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(new ApiError("INVALID_ARGUMENT", "请求参数不合法。", currentRequestId(),
                        Map.of("field", e.getParameterName(), "reason", "缺少必填参数")));
    }

    /** 未匹配路由（含已退役端点）返回 404，不落入通用 500。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(404)
                .body(new ApiError("NOT_FOUND", "请求的资源不存在。", currentRequestId(), Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("unexpected error", e);
        return ResponseEntity.internalServerError()
                .body(new ApiError("INTERNAL_ERROR", "服务内部错误。", currentRequestId(), Map.of()));
    }

    /** 复用请求可观测性过滤器的 request_id（DESIGN 20.2 日志关联）。 */
    private String currentRequestId() {
        String id = org.slf4j.MDC.get("request_id");
        return id != null ? id : "req_" + UUID.randomUUID();
    }
}
