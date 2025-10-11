package kr.hhplus.be.server.infrastructure.logging;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

@Aspect
@Component
public class ApiLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(ApiLoggingAspect.class);
    private static final int MAX_STRING_LENGTH = 120;

    @Pointcut("@within(org.springframework.web.bind.annotation.RestController)")
    void restControllerLayer() {
    }

    @Around("restControllerLayer()")
    public Object logAroundRestController(ProceedingJoinPoint joinPoint) throws Throwable {
        HttpServletRequest request = currentRequest();
        String httpMethod = request != null ? request.getMethod() : "N/A";
        String uri = request != null ? request.getRequestURI() : joinPoint.getSignature().toShortString();
        String clientIp = request != null ? request.getRemoteAddr() : "N/A";

        String traceId = ensureTraceId();
        String argsSummary = summarizeArguments(joinPoint.getArgs());

        long start = System.nanoTime();
        log.info("[API START] method={} uri={} ip={} traceId={} args={}", httpMethod, uri, clientIp, traceId, argsSummary);

        try {
            Object result = joinPoint.proceed();
            long tookMs = (System.nanoTime() - start) / 1_000_000;
            log.info("[API END] method={} uri={} traceId={} durationMs={} result={}", httpMethod, uri, traceId, tookMs, summarizeResult(result));
            return result;
        } catch (Throwable ex) {
            long tookMs = (System.nanoTime() - start) / 1_000_000;
            log.error("[API ERROR] method={} uri={} traceId={} durationMs={} message={}", httpMethod, uri, traceId, tookMs, ex.getMessage(), ex);
            throw ex;
        } finally {
            cleanupTraceId(traceId);
        }
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            return servletAttrs.getRequest();
        }
        return null;
    }

    private String ensureTraceId() {
        String existing = MDC.get("traceId");
        if (StringUtils.hasText(existing)) {
            return existing;
        }
        String generated = UUID.randomUUID().toString();
        MDC.put("traceId", generated);
        MDC.put("_generatedTraceId", "true");
        return generated;
    }

    private void cleanupTraceId(String traceId) {
        String generatedFlag = MDC.get("_generatedTraceId");
        if ("true".equals(generatedFlag)) {
            MDC.remove("traceId");
            MDC.remove("_generatedTraceId");
        }
    }

    private String summarizeArguments(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        return Arrays.stream(args)
                .filter(this::isSafeForLogging)
                .map(this::summarizeValue)
                .map(str -> str.length() > MAX_STRING_LENGTH ? str.substring(0, MAX_STRING_LENGTH) + "…" : str)
                .reduce((left, right) -> left + ", " + right)
                .map(s -> "[" + s + "]")
                .orElse("[]");
    }

    private boolean isSafeForLogging(Object arg) {
        if (arg == null) {
            return true;
        }
        String className = arg.getClass().getName();
        return !(arg instanceof HttpServletRequest)
                && !className.startsWith("org.springframework.validation.BindingResult")
                && !className.startsWith("jakarta.servlet.http.HttpServletResponse")
                && !className.startsWith("org.springframework.web.multipart")
                && !className.startsWith("java.io");
    }

    private String summarizeResult(Object result) {
        if (result == null) {
            return "null";
        }
        return summarizeValue(result);
    }

    private String summarizeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence sequence) {
            return "\"" + truncate(sequence.toString()) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value.getClass().isArray()) {
            return "array(len=" + java.lang.reflect.Array.getLength(value) + ")";
        }
        if (value instanceof Collection<?> collection) {
            return toCollectionSummary(collection);
        }
        if (value instanceof Map<?, ?> map) {
            return "map(size=" + map.size() + ")";
        }
        return value.getClass().getSimpleName();
    }

    private String toCollectionSummary(Collection<?> collection) {
        if (collection.isEmpty()) {
            return "collection(size=0)";
        }
        Object first = collection.iterator().next();
        String elementType = first != null ? first.getClass().getSimpleName() : "null";
        return "collection(size=" + collection.size() + ", element=" + elementType + ")";
    }

    private String truncate(String value) {
        if (value.length() <= MAX_STRING_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_STRING_LENGTH) + "…";
    }
}
