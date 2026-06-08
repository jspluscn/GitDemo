-- 创建数据库
CREATE DATABASE IF NOT EXISTS repo_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE repo_db;

-- 1. 用户表 (基础核心)
CREATE TABLE IF NOT EXISTS t_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户 ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT '用户名',
    password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希',
    email VARCHAR(100) COMMENT '邮箱',
    full_name VARCHAR(50) COMMENT '真实姓名',
    status TINYINT DEFAULT 1 COMMENT '状态：1-正常，0-禁用',
    create_date DATETIME DEFAULT CURRENT_TIMESTAMP,
    last_updated_date DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username),
    INDEX idx_email (email)
) ENGINE=InnoDB COMMENT='用户表';

-- 2. 代码仓库表
CREATE TABLE IF NOT EXISTS t_repository (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    deploy_code VARCHAR(50) NOT NULL COMMENT '部署编码',
    space_code VARCHAR(50) NOT NULL COMMENT '空间编码',
    name VARCHAR(100) NOT NULL COMMENT '仓库名称',
    description VARCHAR(500) COMMENT '描述',
    owner_id BIGINT NOT NULL COMMENT '所有者用户 ID',
    nas_path VARCHAR(255) NOT NULL COMMENT 'NAS 上的绝对路径',
    git_url VARCHAR(255) COMMENT 'Git 远程地址',
    version INT DEFAULT 0 COMMENT '乐观锁版本号',
    is_deleted TINYINT DEFAULT 0,
    create_date DATETIME DEFAULT CURRENT_TIMESTAMP,
    last_updated_date DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_deploy_space (deploy_code, space_code),
    INDEX idx_owner (owner_id),
    INDEX idx_name (name)
) ENGINE=InnoDB COMMENT='代码仓库表';

-- 3. 文件索引表 (业务层元数据，不存内容)
CREATE TABLE IF NOT EXISTS t_file_index (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    deploy_code VARCHAR(50) NOT NULL COMMENT '部署编码',
    space_code VARCHAR(50) NOT NULL COMMENT '空间编码',
    file_path VARCHAR(500) NOT NULL COMMENT '文件相对路径',
    file_type TINYINT NOT NULL COMMENT '类型：1-文件，2-文件夹',
    file_size BIGINT DEFAULT 0 COMMENT '文件大小',
    last_commit_id VARCHAR(64) COMMENT '最近一次 Git Commit ID',
    lock_token VARCHAR(64) COMMENT '当前持有者的锁 Token',
    locked_by BIGINT COMMENT '锁定者用户 ID',
    locked_at DATETIME COMMENT '锁定时间',
    version INT DEFAULT 0 COMMENT '乐观锁版本号',
    is_deleted TINYINT DEFAULT 0,
    create_date DATETIME DEFAULT CURRENT_TIMESTAMP,
    last_updated_date DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_deploy_space_path (deploy_code, space_code, file_path),
    INDEX idx_deploy_space (deploy_code, space_code),
    INDEX idx_lock (locked_by)
) ENGINE=InnoDB COMMENT='文件索引表';

-- 4. 分布式文件锁记录表 (辅助 Redisson，确保持久化状态)
CREATE TABLE IF NOT EXISTS t_file_lock (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    deploy_code VARCHAR(50) NOT NULL COMMENT '部署编码',
    space_code VARCHAR(50) NOT NULL COMMENT '空间编码',
    file_path VARCHAR(500) NOT NULL,
    user_id BIGINT NOT NULL COMMENT '锁定用户',
    lock_token VARCHAR(64) NOT NULL UNIQUE COMMENT '锁令牌',
    expire_time DATETIME NOT NULL COMMENT '过期时间',
    create_date DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_path (deploy_code, space_code, file_path),
    INDEX idx_expire (expire_time)
) ENGINE=InnoDB COMMENT='文件锁持久化记录';

-- 5. Git 提交记录缓存表
CREATE TABLE IF NOT EXISTS t_commit_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    deploy_code VARCHAR(50) NOT NULL COMMENT '部署编码',
    space_code VARCHAR(50) NOT NULL COMMENT '空间编码',
    commit_hash VARCHAR(64) NOT NULL,
    author_id BIGINT COMMENT '提交者用户 ID',
    author_name VARCHAR(50),
    message TEXT,
    commit_time DATETIME,
    parent_hash VARCHAR(64),
    create_date DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_deploy_space_commit (deploy_code, space_code, commit_hash),
    INDEX idx_author (author_id)
) ENGINE=InnoDB COMMENT='Git 提交记录缓存';

-- 6. 文件变更记录表（用户操作产生的未提交变更）
CREATE TABLE IF NOT EXISTS t_file_change (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    deploy_code VARCHAR(50) NOT NULL COMMENT '部署编码',
    space_code VARCHAR(50) NOT NULL COMMENT '空间编码',
    file_path VARCHAR(500) NOT NULL COMMENT '文件路径',
    change_type VARCHAR(20) NOT NULL COMMENT '变更类型：ADD/MODIFY/DELETE',
    new_path VARCHAR(500) COMMENT '新路径（仅重命名时有值）',
    committed TINYINT DEFAULT 0 COMMENT '0-未提交，1-已提交',
    commit_id VARCHAR(64) COMMENT '提交后的 commit hash',
    operator_id BIGINT COMMENT '操作人用户 ID',
    create_date DATETIME DEFAULT CURRENT_TIMESTAMP,
    committed_date DATETIME COMMENT '提交时间',
    INDEX idx_deploy_space_uncommitted (deploy_code, space_code, committed),
    INDEX idx_file (deploy_code, space_code, file_path)
) ENGINE=InnoDB COMMENT='文件变更记录表';


-- 初始化默认管理员用户 (密码为 admin123, 实际生产请加密)
INSERT INTO t_user (username, password_hash, email, full_name) 
VALUES ('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iDJn1qS/6qZG/9qZG/9qZG/9qZG/', 'admin@example.com', 'System Admin')
ON DUPLICATE KEY UPDATE username=username;
