package com.example.repo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_nas_storage_status")
public class NasStorageStatus {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String mountPoint;
    private Long totalSpace;
    private Long freeSpace;
    private Integer status;
    private LocalDateTime lastCheckTime;
    private LocalDateTime updatedAt;
}
