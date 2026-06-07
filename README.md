# Repository Core Service

基于 Spring Cloud 的微服务，集成 **Git 原生存储 (NAS)** + **数据库索引** + **三级锁机制**，支持多用户并发操作文件并提交到代码仓。

## 核心特性

### 1. Git 原生存储 (NAS)
- 使用 JGit 作为 Git 引擎
- **文件存储在 NAS 网络存储上** (支持 NFS/CIFS 挂载)
- 文件内容完全由 Git 管理
- 支持完整的 Git 功能：commit、branch、diff、history
- NAS 健康检查和自动重试机制

### 2. 数据库索引
- `t_repository`: 仓库元数据 (含 NAS 挂载点配置)
- `t_file_index`: 文件索引（路径、大小、锁状态）
- `t_commit_record`: Commit 记录缓存
- `t_nas_storage_status`: NAS 存储健康状态监控

### 3. 三级锁机制

| 级别 | 锁类型 | 实现方式 | 作用 |
|------|--------|----------|------|
| L1 | 仓库级锁 | Redisson RLock | 防止 Git 索引损坏 |
| L2 | 文件级锁 | Redisson RLock | 防止并发编辑冲突 |
| L3 | 业务锁 Token | 数据库 lock_token | 权限验证 + 乐观锁 |

## 技术栈

- Spring Boot 3.2.0
- Spring Cloud 2023.0.0
- MyBatis Plus 3.5.5
- Redisson 3.24.3 (分布式锁)
- JGit 6.7.0 (Git 引擎)
- MySQL 8.0
- **NAS 网络存储** (NFS/CIFS)

## NAS 存储架构

```
┌─────────────────────────────────────────┐
│   Repository Core Service (微服务)      │
│                                         │
│  ┌─────────────┐    ┌──────────────┐   │
│  │ GitManager  │───▶│ NasStorage   │   │
│  │             │    │ Manager      │   │
│  └─────────────┘    └──────────────┘   │
│         │                    │          │
│         ▼                    ▼          │
│  ┌─────────────┐    ┌──────────────┐   │
│  │   JGit      │    │ NAS 健康检查 │   │
│  │   Engine    │    │ 重试机制     │   │
│  └─────────────┘    └──────────────┘   │
└─────────────────────────────────────────┘
              │                │
              ▼                ▼
     ┌─────────────────────────────────┐
     │      NAS 网络存储 (/mnt/nas)    │
     │                                 │
     │  /git-repositories/             │
     │    ├── repo-1/.git              │
     │    ├── repo-1/file1.java        │
     │    ├── repo-2/.git              │
     │    └── repo-2/src/...           │
     │                                 │
     └─────────────────────────────────┘
```

## API 接口

### 仓库管理
```bash
POST   /api/v1/repositories              # 创建仓库
GET    /api/v1/repositories/{id}         # 获取仓库详情
```

### 文件操作
```bash
GET    /api/v1/repositories/{id}/files           # 获取文件列表
GET    /api/v1/repositories/{id}/files/content   # 获取文件内容
POST   /api/v1/repositories/{id}/files/lock      # 锁定文件
POST   /api/v1/repositories/{id}/files/unlock    # 解锁文件
PUT    /api/v1/repositories/{id}/files           # 保存文件变更
```

### 提交管理
```bash
POST   /api/v1/repositories/{id}/commits         # 提交变更
GET    /api/v1/repositories/{id}/commits         # 获取提交历史
```

### NAS 存储监控 (新增)
```bash
GET    /api/v1/storage/health            # NAS 健康检查
GET    /api/v1/storage/info              # NAS 存储信息
```

## 快速开始

### 1. 环境准备
```bash
# 启动 MySQL
docker run -d --name mysql -e MYSQL_ROOT_PASSWORD=root123 -p 3306:3306 mysql:8.0

# 启动 Redis
docker run -d --name redis -p 6379:6379 redis:latest

# 初始化数据库
mysql -u root -p < src/main/resources/db/schema.sql

# 挂载 NAS (示例：NFS)
# 在 Linux 上:
sudo mkdir -p /mnt/nas
sudo mount -t nfs nas-server:/export/git-repos /mnt/nas

# 或在 /etc/fstab 中配置永久挂载:
# nas-server:/export/git-repos  /mnt/nas  nfs  defaults,_netdev  0  0
```

### 2. 配置修改
编辑 `application.yml`:
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/repo_db
    username: root
    password: root123
  data:
    redis:
      host: localhost
      port: 6379

repo:
  storage:
    # NAS 挂载点路径
    base-path: /mnt/nas/git-repositories
    temp-path: /tmp/git-temp
    lfs-enabled: true
    
    # NAS 特定配置
    nas:
      enabled: true
      mount-point: /mnt/nas
      connection-timeout: 30000  # 连接超时 30 秒
      retry-count: 3             # 失败重试 3 次
      health-check-enabled: true # 启用健康检查
      health-check-interval: 60  # 每 60 秒检查一次
```

### 3. 启动服务
```bash
mvn spring-boot:run
```

### 4. 验证 NAS 连接
```bash
# 检查 NAS 健康状态
curl http://localhost:8083/api/v1/storage/health

# 查看 NAS 存储信息
curl http://localhost:8083/api/v1/storage/info
```

## 并发控制流程

### 文件编辑流程
```
1. 用户 A 请求锁定文件 → L2 分布式锁
2. 获取锁成功 → 生成 lockToken → 更新数据库
3. 用户 A 编辑文件 → 保存到 NAS 上的 Git 工作区
4. 用户 A 提交 → 获取 L1 仓库锁 → Git commit → 释放锁
5. 用户 A 解锁 → 清除 lockToken → 释放 L2 锁

用户 B 同时请求:
→ L2 锁已被占用 → 返回冲突 (409)
→ 显示当前锁定用户信息
```

### 乐观锁防冲突
```java
// 更新时检查版本号
UPDATE t_file_index 
SET content = ?, version = version + 1 
WHERE id = ? AND version = expectedVersion

// 如果影响行数=0，说明版本已变化，抛出冲突异常
```

## NAS 高可用特性

### 1. 健康检查
- 启动时自动检测 NAS 可访问性
- 定期后台检查 NAS 状态
- 支持手动触发健康检查 (`GET /api/v1/storage/health`)

### 2. 自动重试
- 目录创建失败自动重试 (指数退避)
- 可配置重试次数和超时时间
- 详细的错误日志记录

### 3. 故障恢复
- NAS 短暂不可用时自动重试
- 持久化错误信息到数据库
- 告警通知 (可集成监控系统)

## 项目结构

```
repository-core-service/
├── src/main/java/com/example/repo/
│   ├── config/
│   │   ├── RedissonConfig.java       # Redisson 配置
│   │   └── RepoStorageConfig.java    # NAS 存储配置
│   ├── controller/
│   │   ├── RepositoryController.java # 仓库 API
│   │   └── StorageController.java    # NAS 监控 API (新增)
│   ├── dto/                          # 数据传输对象
│   ├── entity/                       # 实体类
│   ├── exception/                    # 异常处理
│   ├── manager/
│   │   ├── GitManager.java           # Git 操作
│   │   └── NasStorageManager.java    # NAS 管理 (新增)
│   ├── mapper/                       # MyBatis Mapper
│   └── service/                      # 业务逻辑
├── src/main/resources/
│   ├── db/
│   │   └── schema.sql                # 数据库脚本 (含 NAS 表)
│   └── application.yml               # 配置文件
└── pom.xml
```

## 注意事项

### NAS 配置
1. **挂载点**: 确保 `/mnt/nas` 已正确挂载并有读写权限
2. **网络**: 确保微服务服务器与 NAS 服务器网络通畅
3. **权限**: 运行服务的用户对 NAS 目录有读写权限
4. **备份**: 定期备份 NAS 上的 Git 仓库和数据库

### 性能优化
1. **NFS 挂载选项**: 建议使用 `hard,intr,rsize=32768,wsize=32768`
2. **本地缓存**: 频繁访问的文件可考虑本地缓存
3. **Git LFS**: 大文件 (>100MB) 建议启用 Git LFS
4. **锁超时**: 分布式锁默认 30 秒超时，防止死锁

### 监控告警
1. 定期检查 `/api/v1/storage/health` 接口
2. 监控 NAS 磁盘空间使用率
3. 设置 NAS 不可用时的告警通知
4. 记录所有锁冲突和重试事件

## 常见问题

### Q: NAS 断开连接怎么办？
A: 服务会自动重试 3 次，每次间隔递增。如果持续失败，会返回 503 错误并记录日志。建议配置监控系统实时告警。

### Q: 多个微服务实例如何共享 NAS?
A: 所有实例挂载同一个 NAS 路径，通过 Redisson 分布式锁协调访问。确保 Redis 集群高可用。

### Q: 如何迁移现有 Git 仓库到 NAS?
A: 停止服务 → 复制仓库目录到 NAS → 修改配置指向新路径 → 重启服务。
