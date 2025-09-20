package kr.hhplus.be.server.interfaces.web.dto;

public class ApiEnvelope<T> {
    private final T data;
    public ApiEnvelope(T data) { this.data = data; }
    public T getData() { return data; }
}
