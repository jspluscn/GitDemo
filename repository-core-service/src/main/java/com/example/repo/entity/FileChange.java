package com.example.repo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件变更记录实体
 * 记录用户通过创建/更新/删除接口产生的未提交变更
 */
@Data
@TableName("t_file_change")
public class FileChange {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属仓库 ID */
    private Long repoId;

    /** 文件路径 */
    private String filePath;

    /**
     * 变更类型：
     * ADD    - 新增文件/文件夹
     * MODIFY - 修改文件内容或重命名
     * DELETE - 删除文件/文件夹
     */
    private String changeType;

    /** 新路径（仅重命名时有值） */
    private String newPath;

    /** 是否已提交：0-未提交，1-已提交 */
    private Integer committed;

    /** 提交该变更后的 commit hash */
    private String commitId;

    /** 操作人 */
    private Long operatorId;

    private LocalDateTime createdAt;

    private LocalDateTime committedAt;
}
