package com.example.repo.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("t_repository")
public class Repository {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String deployCode;  // 部署编码
    private String spaceCode;   // 空间编码
    private String name;
    private String description;
    private Long ownerId;
    private String nasPath;
    private String gitUrl;
    private Integer version;
    @TableLogic
    private Integer isDeleted;
    private LocalDateTime createDate;
    private LocalDateTime lastUpdatedDate;
}
