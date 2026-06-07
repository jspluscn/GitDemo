package com.example.repo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_file_index")
public class FileIndex {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long repoId;
    private String filePath;
    private Integer fileType; // 1-文件，2-文件夹
    private Long fileSize;
    private String lastCommitId;
    private Long lockedBy;
    private LocalDateTime lockedAt;
    private Integer version;
    @TableLogic
    private Integer isDeleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long createdBy;
    private Long updatedBy;
}
