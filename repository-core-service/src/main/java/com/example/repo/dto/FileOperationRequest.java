package com.example.repo.dto;

import lombok.Data;

/**
 * 文件操作请求 DTO
 * 用于创建、更新文件操作
 */
@Data
public class FileOperationRequest {
    
    /**
     * 部署编码
     */
    private String deployCode;
    
    /**
     * 空间编码
     */
    private String spaceCode;
    
    /**
     * 文件路径 (相对于仓库根目录)
     */
    private String filePath;
    
    /**
     * 文件内容
     */
    private String content;
    
    /**
     * 操作人
     */
    private String operator;
    
    /**
     * 提交信息
     */
    private String commitMessage;
    
    /**
     * 父目录路径 (用于创建文件夹场景)
     */
    private String parentPath;

    /**
     * 新路径 (用于重命名/移动文件或文件夹)
     */
    private String newPath;

    /**
     * 文件类型: 1-文件, 2-文件夹
     */
    private Integer fileType;

    /**
     * 客户端读取文件时获得的版本号，用于乐观锁冲突检测
     * 更新文件时传入此版本号，服务端会校验当前版本是否一致
     * 如果不一致说明有其他用户已经修改过该文件，拒绝更新并提示冲突
     */
    private Integer expectedVersion;

    /**
     * 是否在操作前自动获取编辑锁（仅用于 update/delete）
     * - true: 操作前检查编辑锁，如果已被其他用户锁定则拒绝
     * - false/null: 不检查编辑锁，仅依靠乐观锁防冲突
     * 
     * 注意：这不会自动获取/释放锁，只是验证是否有其他人的锁
     * 如果需要长时间编辑，请手动调用 /lock/acquire 和 /lock/release
     */
    private Boolean checkEditLock;
}
