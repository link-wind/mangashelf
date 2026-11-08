package com.mangashelf.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
