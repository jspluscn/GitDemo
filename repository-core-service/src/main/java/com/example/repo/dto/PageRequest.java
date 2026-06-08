package com.example.repo.dto;

import lombok.Data;

/**
 * 分页查询请求
 */
@Data
public class PageRequest {
    
    /** 页码（从1开始） */
    private Integer pageNum = 1;
    
    /** 每页大小 */
    private Integer pageSize = 20;
    
    /** 父路径（用于目录树导航） */
    private String parentPath;
    
    /** 部署编码 */
    private String deployCode;
    
    /** 空间编码 */
    private String spaceCode;
}
