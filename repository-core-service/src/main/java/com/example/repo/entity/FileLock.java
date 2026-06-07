package com.example.repo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_file_lock")
public class FileLock {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long repoId;
    private String filePath;
    private Long userId;
    private String lockToken;
    private LocalDateTime expireTime;
    private LocalDateTime createdAt;
}
