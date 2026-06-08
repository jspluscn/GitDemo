package com.example.repo.dto;

import lombok.Data;

/**
 * 文件锁定请求 DTO
 */
@Data
public class FileLockRequest {
    private String deployCode;  // 部署编码
    private String spaceCode;   // 空间编码
    private String filePath;
    private Long userId;

    /** 操作人用户名，用于查找用户 ID */
    private String operator;

    /** 锁过期时间（秒），默认 300 秒（5 分钟） */
    private Integer expireSeconds;
}
