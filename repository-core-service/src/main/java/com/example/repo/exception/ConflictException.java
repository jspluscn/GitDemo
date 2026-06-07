package com.example.repo.exception;

/**
 * 文件冲突异常
 * 当乐观锁检测到文件版本不一致时抛出
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }

    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
