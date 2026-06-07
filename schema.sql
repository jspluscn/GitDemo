-- 数据库初始化脚本
-- 基于 Spring Cloud 的多用户文件协作与代码仓管理系统

-- 1. 用户表
CREATE TABLE IF NOT EXISTS `t_user` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '用户 ID',
    `username` VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    `password_hash` VARCHAR(255) NOT NULL COMMENT '密码哈希',
    `email` VARCHAR(100) COMMENT '邮箱',
    `full_name` VARCHAR(100) COMMENT '全名',
    `status` TINYINT DEFAULT 1 COMMENT '状态：1-正常，0-禁用',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX `idx_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 2. 仓库表
CREATE TABLE IF NOT EXISTS `t_repository` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '仓库 ID',
    `name` VARCHAR(100) NOT NULL COMMENT '仓库名称',
    `description` VARCHAR(500) COMMENT '描述',
    `owner_id` BIGINT NOT NULL COMMENT '所有者 ID',
    `nas_path` VARCHAR(255) NOT NULL COMMENT 'NAS 存储路径',
    `git_remote_url` VARCHAR(255) COMMENT 'Git 远程地址',
    `status` TINYINT DEFAULT 1 COMMENT '状态：1-正常，0-禁用',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX `idx_owner_id` (`owner_id`),
    INDEX `idx_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库表';

-- 3. 文件索引表
CREATE TABLE IF NOT EXISTS `t_file_index` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '文件 ID',
    `repo_id` BIGINT NOT NULL COMMENT '仓库 ID',
    `file_path` VARCHAR(500) NOT NULL COMMENT '文件路径',
    `file_type` TINYINT NOT NULL DEFAULT 1 COMMENT '类型：1-文件，2-文件夹',
    `file_size` BIGINT DEFAULT 0 COMMENT '文件大小 (字节)',
    `last_commit_id` VARCHAR(64) COMMENT '最后一次 Git Commit ID',
    `locked_by` BIGINT COMMENT '锁定者 ID',
    `locked_at` DATETIME COMMENT '锁定时间',
    `version` INT DEFAULT 0 COMMENT '版本号 (乐观锁)',
    `is_deleted` TINYINT DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `created_by` BIGINT COMMENT '创建者 ID',
    `updated_by` BIGINT COMMENT '最后更新者 ID',
    UNIQUE KEY `uk_repo_path` (`repo_id`, `file_path`),
    INDEX `idx_repo_id` (`repo_id`),
    INDEX `idx_file_path` (`file_path`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件索引表';

-- 4. 文件锁表
CREATE TABLE IF NOT EXISTS `t_file_lock` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '锁 ID',
    `repo_id` BIGINT NOT NULL COMMENT '仓库 ID',
    `file_path` VARCHAR(500) NOT NULL COMMENT '文件路径',
    `user_id` BIGINT NOT NULL COMMENT '锁定用户 ID',
    `lock_token` VARCHAR(64) NOT NULL COMMENT '锁 Token',
    `expire_time` DATETIME NOT NULL COMMENT '过期时间',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY `uk_repo_path_lock` (`repo_id`, `file_path`, `lock_token`),
    INDEX `idx_expire_time` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件锁表';

-- 5. 提交记录表
CREATE TABLE IF NOT EXISTS `t_commit_record` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '记录 ID',
    `repo_id` BIGINT NOT NULL COMMENT '仓库 ID',
    `commit_hash` VARCHAR(64) NOT NULL COMMENT 'Git Commit Hash',
    `file_path` VARCHAR(500) COMMENT '涉及的文件路径',
    `message` VARCHAR(500) COMMENT '提交信息',
    `author_name` VARCHAR(100) COMMENT '作者姓名',
    `author_email` VARCHAR(100) COMMENT '作者邮箱',
    `commit_time` DATETIME COMMENT '提交时间',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_repo_commit` (`repo_id`, `commit_hash`),
    INDEX `idx_file_path` (`file_path`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Git 提交记录表';

-- 6. NAS 存储状态表
CREATE TABLE IF NOT EXISTS `t_nas_storage_status` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '记录 ID',
    `nas_path` VARCHAR(255) NOT NULL UNIQUE COMMENT 'NAS 挂载路径',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1-正常，0-异常',
    `total_space` BIGINT COMMENT '总空间 (字节)',
    `used_space` BIGINT COMMENT '已用空间 (字节)',
    `free_space` BIGINT COMMENT '剩余空间 (字节)',
    `last_check_time` DATETIME COMMENT '最后检查时间',
    `error_message` VARCHAR(500) COMMENT '错误信息',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='NAS 存储状态监控表';

-- 初始化数据

-- 插入测试用户 (密码为加密后的哈希值，实际使用时请使用 BCrypt 加密)
INSERT INTO `t_user` (`username`, `password_hash`, `email`, `full_name`, `status`) VALUES
('zhangsan', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lqkkO9QS3TzCjH3rS', 'zhangsan@example.com', '张三', 1),
('lisi', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lqkkO9QS3TzCjH3rS', 'lisi@example.com', '李四', 1),
('wangwu', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lqkkO9QS3TzCjH3rS', 'wangwu@example.com', '王五', 1);

-- 插入测试仓库
INSERT INTO `t_repository` (`name`, `description`, `owner_id`, `nas_path`, `status`) VALUES
('demo-repo', '演示仓库', 1, '/mnt/nas/git-repositories/repo_1', 1);

-- 插入 NAS 状态记录
INSERT INTO `t_nas_storage_status` (`nas_path`, `status`, `last_check_time`) VALUES
('/mnt/nas/git-repositories', 1, NOW());
