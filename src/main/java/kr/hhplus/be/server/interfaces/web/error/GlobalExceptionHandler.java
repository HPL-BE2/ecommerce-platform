package kr.hhplus.be.server.interfaces.web.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.lang.reflect.InvocationTargetException;
import java.time.OffsetDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentNotValidException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleValidation(Exception ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다.");
        pd.setType(java.net.URI.create("/errors/validation"));
        pd.setTitle("Validation error");
        pd.setProperty("errors", ex.getMessage());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ProblemDetail handleOther(Exception ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");
        pd.setType(java.net.URI.create("/errors/internal"));
        pd.setTitle("Internal server error");
        return pd;
    }

    //NotFoundException
    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(java.net.URI.create("/errors/not-found"));
        pd.setTitle("Resource not found");
        return pd;
    }

    //ApiException
    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex, HttpServletRequest req) {
        var pd = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage()); // detail = 예외 메시지 그대로
        if (ex.getType() != null && !ex.getType().isBlank()) {
            pd.setType(java.net.URI.create(ex.getType()));
        }
        if (ex.getTitle() != null && !ex.getTitle().isBlank()) {
            pd.setTitle(ex.getTitle());
        }
        // 추가 프로퍼티
        ex.getProps().forEach(pd::setProperty);
        enrich(pd, req, ex); // 너가 이미 만든 공통 메타(경로/메서드/타임스탬프) 붙이는 헬퍼
        return pd;
    }

    // ---------- helpers ----------
    private static void enrich(ProblemDetail pd, HttpServletRequest req, Exception ex) {
        pd.setProperty("timestamp", OffsetDateTime.now().toString());
        pd.setProperty("path", req.getRequestURI());
        pd.setProperty("method", req.getMethod());
        // 필요하면 traceId/correlationId를 여기에 추가
        // pd.setProperty("traceId", MDC.get("traceId"));
        // 개발중에는 rootCause도 보여주고, 운영에서는 숨겨도 됨
        pd.setProperty("rootCause", unwrap(ex).getClass().getName());
    }

    /** InvocationTargetException 등 래퍼 바깥으로 루트 원인 꺼내기 */
    private static Throwable unwrap(Throwable t) {
        if (t instanceof InvocationTargetException ite && ite.getTargetException() != null) {
            return unwrap(ite.getTargetException());
        }
        Throwable cause = t.getCause();
        return (cause != null && cause != t) ? unwrap(cause) : t;
    }

}
