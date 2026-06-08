package com.example.repo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分页响应（支持树状结构）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse {
    
    /** 当前页数据列表 */
    private List<FileChangeNode> records;
    
    /** 总记录数 */
    private Long total;
    
    /** 当前页码 */
    private Integer pageNum;
    
    /** 每页大小 */
    private Integer pageSize;
    
    /** 总页数 */
    private Integer totalPages;
    
    /**
     * 文件变更节点（树状结构）
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FileChangeNode {
        /** 变更记录ID */
        private Long id;
        
        /** 文件/文件夹名称 */
        private String name;
        
        /** 文件路径 */
        private String filePath;
        
        /** 变更类型：ADD/MODIFY/DELETE */
        private String changeType;
        
        /** 新路径（重命名时） */
        private String newPath;
        
        /** 是否为文件夹 */
        private Boolean isFolder;
        
        /** 子节点列表 */
        private List<FileChangeNode> children;
        
        /** 操作人 */
        private Long operatorId;
        
        /** 创建时间 */
        private LocalDateTime createdAt;
    }
}
