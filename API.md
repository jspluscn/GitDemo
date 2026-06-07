# 文件操作 API 文档

## 概述

本系统提供三个核心文件操作接口：**创建文件**、**更新文件**、**删除文件**。所有操作都内置了三级锁机制，确保多用户并发场景下的数据一致性。

---

## 核心接口

### 1. 创建文件

**接口**: `POST /api/files/create`

**描述**: 创建新文件并自动提交到 Git 仓库

**请求体**:
```json
{
  "repoId": 1,
  "filePath": "docs/readme.md",
  "content": "# Hello World\n\nThis is a new file.",
  "operator": "zhangsan",
  "commitMessage": "Initial commit: add readme.md"
}
```

**字段说明**:
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| repoId | Long | 是 | 仓库 ID |
| filePath | String | 是 | 文件路径（相对于仓库根目录） |
| content | String | 否 | 文件内容（默认为空字符串） |
| operator | String | 是 | 操作人用户名 |
| commitMessage | String | 否 | 提交信息（默认："Create file: {filePath}"） |

**响应成功**:
```json
{
  "code": 200,
  "message": "success",
  "data": "a1b2c3d4e5f6789012345678901234567890abcd"
}
```
`data` 字段为 Git Commit Hash

**响应失败**:
```json
{
  "code": 409,
  "message": "File already exists: docs/readme.md",
  "data": null
}
```

**可能的错误码**:
- `409`: 文件已存在 / 文件被其他用户锁定 / NAS 不可用
- `500`: 服务器内部错误

---

### 2. 更新文件

**接口**: `PUT /api/files/update`

**描述**: 更新现有文件内容并自动提交到 Git 仓库

**请求体**:
```json
{
  "repoId": 1,
  "filePath": "docs/readme.md",
  "content": "# Hello World\n\nUpdated content here.",
  "operator": "zhangsan",
  "commitMessage": "Update readme.md with new content"
}
```

**字段说明**:
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| repoId | Long | 是 | 仓库 ID |
| filePath | String | 是 | 文件路径 |
| content | String | **是** | 新的文件内容（不能为空） |
| operator | String | 是 | 操作人用户名 |
| commitMessage | String | 否 | 提交信息（默认："Update file: {filePath}"） |

**响应成功**:
```json
{
  "code": 200,
  "message": "success",
  "data": "b2c3d4e5f6789012345678901234567890abcde"
}
```

**响应失败**:
```json
{
  "code": 409,
  "message": "File is currently locked by another user",
  "data": null
}
```

**可能的错误码**:
- `409`: 文件不存在 / 文件被其他用户锁定 / 内容为空 / NAS 不可用
- `500`: 服务器内部错误

---

### 3. 删除文件

**接口**: `DELETE /api/files/delete`

**描述**: 删除文件并从 Git 仓库中移除追踪

**请求参数** (Query Parameters):
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| repoId | Long | 是 | 仓库 ID |
| filePath | String | 是 | 文件路径 |
| operator | String | 是 | 操作人用户名 |

**请求示例**:
```
DELETE /api/files/delete?repoId=1&filePath=docs/readme.md&operator=zhangsan
```

**响应成功**:
```json
{
  "code": 200,
  "message": "success",
  "data": "c3d4e5f6789012345678901234567890abcdef"
}
```

**响应失败**:
```json
{
  "code": 409,
  "message": "File not found: docs/readme.md",
  "data": null
}
```

**可能的错误码**:
- `409`: 文件不存在 / 文件被其他用户锁定 / NAS 不可用
- `500`: 服务器内部错误

---

## 辅助接口

### 获取文件列表

**接口**: `GET /api/files/list`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| repoId | Long | 是 | 仓库 ID |
| parentPath | String | 否 | 父目录路径（用于筛选） |

**示例**:
```
GET /api/files/list?repoId=1&parentPath=docs/
```

---

### 获取文件历史

**接口**: `GET /api/files/history`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| repoId | Long | 是 | 仓库 ID |
| filePath | String | 是 | 文件路径 |

**响应示例**:
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "commitHash": "a1b2c3d4...",
      "authorName": "zhangsan",
      "authorEmail": "zhangsan@local",
      "message": "Update readme.md",
      "commitTime": "2024-01-15T10:30:00"
    },
    {
      "commitHash": "b2c3d4e5...",
      "authorName": "lisi",
      "authorEmail": "lisi@local",
      "message": "Initial commit",
      "commitTime": "2024-01-14T09:00:00"
    }
  ]
}
```

---

### 读取文件内容

**接口**: `GET /api/files/content`

**请求参数**:
| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| repoId | Long | 是 | 仓库 ID |
| filePath | String | 是 | 文件路径 |
| commitHash | String | 否 | 指定版本（不传则读取最新版本） |

**示例**:
```
GET /api/files/content?repoId=1&filePath=docs/readme.md
```

---

## 并发控制机制

系统采用**三级锁机制**确保并发安全：

### L1 - 仓库级锁 (分布式锁)
- **目的**: 防止 Git 索引文件损坏
- **实现**: Redisson 分布式锁
- **粒度**: 整个仓库
- **超时时间**: 60 秒

### L2 - 文件级锁 (分布式锁)
- **目的**: 防止业务层面的并发写入
- **实现**: Redisson 分布式锁
- **粒度**: 单个文件
- **超时时间**: 30 秒
- **等待时间**: 3 秒

### L3 - 业务 Token 锁 (乐观锁)
- **目的**: 验证操作权限和会话有效性
- **实现**: 数据库 version 字段 + 用户校验
- **特点**: 每次操作自动获取和释放

---

## 使用流程示例

### 场景 1: 用户创建文件
```bash
# 1. 创建文件
curl -X POST http://localhost:8080/api/files/create \
  -H "Content-Type: application/json" \
  -d '{
    "repoId": 1,
    "filePath": "test.txt",
    "content": "Hello World",
    "operator": "zhangsan"
  }'

# 返回: {"code":200,"data":"abc123..."}
```

### 场景 2: 用户更新文件
```bash
# 2. 更新同一文件
curl -X PUT http://localhost:8080/api/files/update \
  -H "Content-Type: application/json" \
  -d '{
    "repoId": 1,
    "filePath": "test.txt",
    "content": "Updated Content",
    "operator": "zhangsan"
  }'
```

### 场景 3: 并发冲突处理
```bash
# 用户 A 和用户 B 同时尝试更新同一文件
# 先到的请求成功，后到的请求返回 409 错误：
# {"code":409,"message":"File is currently locked by another user"}
```

### 场景 4: 删除文件
```bash
# 3. 删除文件
curl -X DELETE "http://localhost:8080/api/files/delete?repoId=1&filePath=test.txt&operator=zhangsan"
```

---

## 注意事项

1. **用户必须存在**: `operator` 字段必须是系统中已注册的用户名
2. **路径规范**: 文件路径使用正斜杠 `/`，相对于仓库根目录
3. **自动提交**: 所有操作都会自动生成 Git Commit，无需手动提交
4. **并发安全**: 系统会自动处理并发冲突，返回明确的错误提示
5. **NAS 依赖**: 当 NAS 存储不可用时，所有写操作会被阻断
