package com.mengying.fqnovel.web;

import com.mengying.fqnovel.service.AutoRestartService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.io.EOFException;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AutoRestartService autoRestartService;

    public GlobalExceptionHandler(AutoRestartService autoRestartService) {
        this.autoRestartService = autoRestartService;
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public Object handleAsyncTimeout(AsyncRequestTimeoutException ex, HttpServletRequest request) {
        autoRestartService.recordFailure("ASYNC_TIMEOUT");
        log.warn("异步请求超时", ex);
        return buildError(HttpStatus.GATEWAY_TIMEOUT, "request timeout", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Object handleMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        if (log.isDebugEnabled()) {
            log.debug("请求体解析失败: {}", ex.getMessage());
        }
        return buildError(HttpStatus.BAD_REQUEST, "Invalid request body format", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Object handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        if (log.isDebugEnabled()) {
            log.debug("不支持的请求方法: {}", ex.getMessage());
        }
        return buildError(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed: " + ex.getMethod(), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Object handleMissingRequestParameter(MissingServletRequestParameterException ex, HttpServletRequest request) {
        String parameterName = ex.getParameterName();
        if (log.isDebugEnabled()) {
            log.debug("缺少请求参数: {}", parameterName);
        }
        return buildError(HttpStatus.BAD_REQUEST, "Missing required parameter: " + parameterName, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Object handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String parameterName = ex.getName();
        if (log.isDebugEnabled()) {
            log.debug("请求参数类型错误: {} = {}", parameterName, ex.getValue());
        }
        return buildError(HttpStatus.BAD_REQUEST, "Invalid parameter type: " + parameterName, request);
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    public Object handleServletBinding(ServletRequestBindingException ex, HttpServletRequest request) {
        if (log.isDebugEnabled()) {
            log.debug("请求绑定失败: {}", ex.getMessage());
        }
        return buildError(HttpStatus.BAD_REQUEST, "Bad request", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Object handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, messageOrDefault(ex.getMessage(), "bad request"), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public Object handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        if (log.isDebugEnabled()) {
            log.debug("静态资源不存在: {}", ex.getMessage());
        }
        return buildError(HttpStatus.NOT_FOUND, "Not Found", request);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public Object handleNoHandlerFound(NoHandlerFoundException ex, HttpServletRequest request) {
        if (log.isDebugEnabled()) {
            log.debug("请求路径不存在: {}", ex.getRequestURL());
        }
        return buildError(HttpStatus.NOT_FOUND, "Not Found", request);
    }

    /**
     * 客户端主动断开连接（浏览器取消请求/网络中断）属于常见噪音，不按服务异常记录。
     * 使用 void 返回避免再次写响应触发二次 Broken pipe 日志。
     */
    @ExceptionHandler({SocketException.class, EOFException.class, ClosedChannelException.class})
    public void handleClientAbort(Exception ex) {
        if (log.isDebugEnabled()) {
            log.debug("客户端已断开连接: {}", ex.getMessage());
        }
    }

    /**
     * 异步响应写回阶段，若客户端已经断开，Spring 会抛出 AsyncRequestNotUsableException。
     * 该场景属于常见网络噪音，不应继续转发 /error，否则会触发重复异常日志。
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException ex) {
        if (log.isDebugEnabled()) {
            log.debug("异步响应不可用（客户端可能已断开）: {}", ex.getMessage());
        }
    }

    /**
     * JSON 写回阶段常会包裹一层 HttpMessageNotWritableException。
     * 若根因是客户端断开，则按噪音处理；否则继续抛出交给统一兜底。
     */
    @ExceptionHandler(HttpMessageNotWritableException.class)
    public void handleMessageNotWritable(HttpMessageNotWritableException ex) throws HttpMessageNotWritableException {
        if (!isClientAbortLike(ex)) {
            throw ex;
        }
        if (log.isDebugEnabled()) {
            log.debug("响应写回失败（客户端已断开）: {}", ex.getMessage());
        }
    }

    @ExceptionHandler(Exception.class)
    public Object handleException(Exception ex, HttpServletRequest request) {
        log.error("全局异常捕获: {}", ex.getMessage(), ex);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", request);
    }

    private static String messageOrDefault(String message, String defaultMessage) {
        return Objects.requireNonNullElse(message, defaultMessage);
    }

    private static boolean isClientAbortLike(Throwable throwable) {
        return hasCause(throwable, SocketException.class)
            || hasCause(throwable, EOFException.class)
            || hasCause(throwable, ClosedChannelException.class)
            || hasCause(throwable, AsyncRequestNotUsableException.class)
            || hasCauseClassName(throwable, "org.apache.catalina.connector.ClientAbortException");
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (type.isInstance(cursor)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static boolean hasCauseClassName(Throwable throwable, String className) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor.getClass().getName().equals(className)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static Object buildError(HttpStatus status, String message, HttpServletRequest request) {
        if (request != null) {
            request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status.value());
            request.setAttribute(RequestDispatcher.ERROR_MESSAGE, message);
        }
        ModelAndView modelAndView = new ModelAndView("forward:/error");
        modelAndView.setStatus(status);
        return modelAndView;
    }
}
