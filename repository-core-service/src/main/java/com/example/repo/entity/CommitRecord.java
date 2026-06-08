package com.example.repo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_commit_record")
public class CommitRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String deployCode;  // 部署编码
    private String spaceCode;   // 空间编码
    private String commitHash;
    private Long authorId;
    private String authorName;
    private String message;
    private LocalDateTime commitTime;
    private String parentHash;
    private LocalDateTime createDate;
}
