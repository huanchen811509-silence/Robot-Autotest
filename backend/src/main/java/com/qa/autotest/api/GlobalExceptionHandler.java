package com.qa.autotest.api;

import com.qa.autotest.application.report.ExportRejectedException;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常映射（FR-v0.1-06）：把异常翻译成 {@link ApiResponse}，同时给出正确的 HTTP 状态码。
 *
 * <p>三类分支：参数校验 → 400；业务异常 → 按错误码定状态；其余 → 500 并记录堆栈。
 *
 * <p><b>状态码不能一律落到兜底 500</b>：405 和 404 都是「请求本身不成立」，不是服务故障。
 * 误报成 500 会有两个后果 —— 对外看起来像故障，对内把真正的 500 淹在噪声里。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidArgument(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("参数校验失败");
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.INVALID_ARGUMENT, detail));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.INVALID_ARGUMENT, ex.getMessage()));
    }

    /**
     * 查询参数缺失或类型不对：`GET /api/reports/RUN-1`（漏 projectId）、
     * `?page=abc` 这类请求本身不成立，必须 400。
     *
     * <p>这两个异常默认会落到下面的兜底分支变成 **500「服务内部错误」**：
     * 前端漏传一个参数就会被当成服务故障，真正的 500 也被淹掉
     * （与 405、路径不存在同类，均由 2026-11-06 的 {@code ReportControllerTest} 实测暴露）。
     */
    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> handleBadRequestParam(Exception ex) {
        String detail = ex instanceof MissingServletRequestParameterException missing
                ? "缺少参数：" + missing.getParameterName()
                : "参数类型不正确：" + ((MethodArgumentTypeMismatchException) ex).getName();
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.INVALID_ARGUMENT, detail));
    }

    /**
     * 外部版导出缺项：除中文说明外，把 metadataMissing 数组放进 data，
     * 前端可直接逐项展示，不用再解析 message。
     */
    @ExceptionHandler(ExportRejectedException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleExportRejected(
            ExportRejectedException ex) {
        ApiResponse<Map<String, Object>> body = new ApiResponse<>(
                ex.errorCode().code(), ex.errorCode().codeName(), ex.getMessage(),
                Map.of("metadataMissing", ex.metadataMissing()));
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 请求方法不支持（例如对只读的审计接口发起 DELETE）：
     * 必须返回 405，不能落到兜底分支变成 500，否则「只读接口」对外看起来像故障。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.fail(ErrorCode.METHOD_NOT_ALLOWED,
                        "请求方法不支持：" + ex.getMethod()));
    }

    /**
     * 路径不存在：Spring 6.1 起，未匹配到任何 handler 的请求会抛
     * {@link NoResourceFoundException}（走静态资源处理器）。
     *
     * <p>以前这个异常落到下面的 {@link #handleUnexpected} 兜底分支，于是
     * <b>{@code GET /} 返回 500「服务内部错误」并打一条 ERROR 堆栈</b>。后果不是"多打一行日志"：
     * 前端路由写错、用户输错 URL、扫描器探测，全都会伪装成服务端故障，真正的 500 被淹掉。
     *
     * <p>由 2026-11-04 启动自检（{@code scripts/boot-check.sh}）实测发现：真实启动后
     * {@code /} → 500，{@code /api/*} → 401 正常。这是"从没起过 Spring 上下文"才会漏掉的一类问题。
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception ex) {
        String detail = ex instanceof NoResourceFoundException notFound
                ? "路径不存在：" + notFound.getResourcePath()
                : "路径不存在";
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ErrorCode.NOT_FOUND, detail));
    }

    /**
     * 上传超过容器上限（大于 21 MB）：属于请求本身不成立，返回 400 明确错误码，
     * 不能落到兜底分支变成「服务内部错误」。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.INVALID_ARGUMENT, "上传文件超过 21 MB 上限"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        HttpStatus status = switch (ex.errorCode()) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case SAFETY_LEVEL_BLOCKED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case TOO_MANY_REQUESTS -> HttpStatus.TOO_MANY_REQUESTS;
            case DEPENDENCY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status)
                .body(ApiResponse.fail(ex.errorCode(), ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("未预期异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, "服务内部错误"));
    }
}
