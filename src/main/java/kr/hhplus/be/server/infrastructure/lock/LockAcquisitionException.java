package kr.hhplus.be.server.infrastructure.lock;

/**
 * 분산락 획득 실패 예외
 * <p>
 * 지정된 대기 시간(waitTime) 내에 락을 획득하지 못했을 때 발생합니다.
 * </p>
 */
public class LockAcquisitionException extends RuntimeException {

    public LockAcquisitionException(String message) {
        super(message);
    }

    public LockAcquisitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
