package org.acme.solver.validation;

/**
 * 검증 실패 시 발생하는 예외.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Object... args) {
        super(String.format(message, args));
    }
}
