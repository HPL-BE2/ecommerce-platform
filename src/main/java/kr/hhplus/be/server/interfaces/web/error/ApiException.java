package kr.hhplus.be.server.interfaces.web.error;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

@Getter
public class ApiException extends RuntimeException{
    private final HttpStatus status;
    private final String type;   // e.g. "/errors/total-mismatch"
    private final String title;  // e.g. "Conflict"
    private final Map<String, Object> props = new LinkedHashMap<>();

    public ApiException with(String key, Object value) { this.props.put(key, value); return this; }

    public ApiException(HttpStatus status, String type, String title, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
    }

    // 편의 팩토리
    public static ApiException badRequest(String type, String title, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, type, title, detail);
    }
    public static ApiException notFound(String type, String title, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, type, title, detail);
    }
    public static ApiException conflict(String type, String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, type, title, detail);
    }
    public static ApiException internal(String type, String title, String detail) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, type, title, detail);
    }
}
