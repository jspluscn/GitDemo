package com.example.repo.dto;

import lombok.Data;

/**
 * 文件提交请求 DTO
 */
@Data
public class FileCommitRequest {
    private Long repoId;
    private String filePath;
    private String content;
    private String message;
    private Long userId;
    private String lockToken;
}
