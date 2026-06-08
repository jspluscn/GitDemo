package com.example.repo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_file_lock")
public class FileLock {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String deployCode;  // 部署编码
    private String spaceCode;   // 空间编码
    private String filePath;
    private Long userId;
    private String lockToken;
    private LocalDateTime expireTime;
    private LocalDateTime createDate;
}
