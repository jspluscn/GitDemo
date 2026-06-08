package com.example.repo.dto;

import lombok.Data;

import java.util.List;

/**
 * 提交变更请求 DTO
 */
@Data
public class CommitRequest {

    /** 部署编码 */
    private String deployCode;

    /** 空间编码 */
    private String spaceCode;

    /** 操作人 */
    private String operator;

    /** Git 提交信息 */
    private String commitMessage;

    /**
     * 选中的变更 ID 列表（用户勾选的变更记录 ID）
     * 为空时提交所有未提交的变更
     */
    private List<Long> selectedChangeIds;

    /** 是否推送到远程仓库（默认 false，仅本地提交） */
    private Boolean pushToRemote;
}
