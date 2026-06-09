# Repository Core Service API 使用文档

## 📋 目录

- [概述](#概述)
- [基础信息](#基础信息)
- [并发控制机制](#并发控制机制)
- [接口列表](#接口列表)
  - [文件操作](#1-文件操作)
  - [变更管理](#2-变更管理)
  - [查询接口](#3-查询接口)
  - [编辑锁管理](#4-编辑锁管理)
- [使用场景示例](#使用场景示例)
- [错误处理](#错误处理)
- [最佳实践](#最佳实践)

---

## 概述

Repository Core Service 提供基于 Git 的文件协作管理服务，支持多人协同编辑、版本控制和变更管理。

**核心工作流：**
```
创建/更新/删除（记录变更） → 查看变更 → 勾选提交 → Git Commit/Push
```

---

## 基础信息

### Base URL
```
http://{host}:{port}/api/v1/files
```

### 通用响应格式

**成功响应：**
```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

**错误响应：**
```json
{
  "code": 409,
  "message": "错误描述信息",
  "data": null
}
```

### 通用参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| deployCode | String | 是 | 部署编码（项目标识） |
| spaceCode | String | 是 | 空间编码（仓库标识） |
| operator | String | 是 | 操作人用户名 |

---

## 并发控制机制

系统采用**四层并发控制策略**，确保数据一致性：

### 第1层：乐观锁（Optimistic Lock）
- **机制**：通过 `expectedVersion` 字段检测版本冲突
- **适用**：Update 操作
- **必须性**：⭐⭐⭐⭐⭐ 强烈推荐

### 第2层：编辑锁（Edit Lock）
- **机制**：手动获取/释放的长时间锁
- **适用**：在线编辑器等长时间编辑场景
- **必须性**：⭐⭐ 可选，用于增强协作体验

### 第3层：分布式文件锁（Distributed File Lock）
- **机制**：基于 Redisson 的 RLock
- **适用**：所有文件操作
- **必须性**：⭐⭐⭐⭐⭐ 内部自动管理

### 第4层：分布式仓库锁（Distributed Repo Lock）
- **机制**：基于 Redisson 的 RLock
- **适用**：Git 操作
- **必须性**：⭐⭐⭐⭐⭐ 内部自动管理

---

## 接口列表

### 1. 文件操作

#### 1.1 创建文件或文件夹

**接口：** `POST /create`

**描述：** 创建新文件或文件夹，仅写入磁盘并记录变更，不执行 Git Commit。

**请求体：**
```json
{
  "deployCode": "project-001",
  "spaceCode": "repo-main",
  "filePath": "src/config.yml",
  "content": "key: value",
  "operator": "zhangsan",
  "fileType": 1
}
```

**参数说明：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| deployCode | String | ✅ | - | 部署编码 |
| spaceCode | String | ✅ | - | 空间编码 |
| filePath | String | ✅ | - | 文件路径（相对于仓库根目录） |
| content | String | ❌ | "" | 文件内容（创建文件时需要） |
| operator | String | ✅ | - | 操作人用户名 |
| fileType | Integer | ❌ | 1 | 文件类型：1-文件，2-文件夹 |

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "deployCode": "project-001",
    "spaceCode": "repo-main",
    "filePath": "src/config.yml",
    "changeType": "ADD",
    "committed": 0,
    "operatorId": 1001,
    "createDate": "2026-06-09T15:30:00"
  }
}
```

**错误场景：**
- 文件已存在：`"File already exists: src/config.yml"`
- 父目录不存在：相关错误提示

---

#### 1.2 更新文件或文件夹

**接口：** `PUT /update`

**描述：** 更新文件内容、重命名文件或移动文件位置。

**请求体：**
```json
{
  "deployCode": "project-001",
  "spaceCode": "repo-main",
  "filePath": "src/config.yml",
  "content": "new key: new value",
  "operator": "zhangsan",
  "expectedVersion": 5,
  "checkEditLock": true,
  "newPath": "src/new-config.yml"
}
```

**参数说明：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| deployCode | String | ✅ | 部署编码 |
| spaceCode | String | ✅ | 空间编码 |
| filePath | String | ✅ | 当前文件路径 |
| content | String | ⚠️ | 新内容（更新内容时需要） |
| operator | String | ✅ | 操作人用户名 |
| expectedVersion | Integer | ⚠️ | **推荐提供**：客户端读取时的版本号，用于乐观锁检测 |
| checkEditLock | Boolean | ❌ | 是否检查编辑锁（默认 false）。设为 true 可防止覆盖他人正在编辑的文件 |
| newPath | String | ❌ | 新路径（重命名/移动时需要） |

**使用场景：**

**场景 A：仅更新内容**
```json
{
  "filePath": "config.yml",
  "content": "new content",
  "expectedVersion": 5,
  "checkEditLock": false
}
```

**场景 B：重命名文件**
```json
{
  "filePath": "old-name.yml",
  "newPath": "new-name.yml",
  "expectedVersion": 5
}
```

**场景 C：移动并重命名**
```json
{
  "filePath": "src/old.yml",
  "newPath": "docs/new.yml",
  "content": "updated content",
  "expectedVersion": 5
}
```

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 2,
    "filePath": "src/config.yml",
    "changeType": "MODIFY",
    "newPath": null,
    "committed": 0
  }
}
```

**错误场景：**
- 版本冲突（409）：`"File conflict detected: file 'config.yml' has been modified by another user (expected version 5, current version 6)"`
- 文件被锁定（409）：`"File 'config.yml' is currently being edited by user 'lisi'"`
- 文件不存在：`"File or folder not found: config.yml"`

---

#### 1.3 删除文件或文件夹

**接口：** `DELETE /delete`

**描述：** 删除文件或文件夹（文件夹会递归删除所有子项）。

**请求参数（Query String）：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| deployCode | String | ✅ | - | 部署编码 |
| spaceCode | String | ✅ | - | 空间编码 |
| filePath | String | ✅ | - | 要删除的文件/文件夹路径 |
| operator | String | ✅ | - | 操作人用户名 |
| checkEditLock | Boolean | ❌ | false | 是否检查编辑锁 |

**请求示例：**
```
DELETE /api/v1/files/delete?deployCode=project-001&spaceCode=repo-main&filePath=src/old.yml&operator=zhangsan&checkEditLock=true
```

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 3,
    "filePath": "src/old.yml",
    "changeType": "DELETE",
    "committed": 0
  }
}
```

**错误场景：**
- 文件不存在：`"File or folder not found: src/old.yml"`
- 文件被锁定：`"File 'src/old.yml' is currently being edited by user 'lisi'"`

---

### 2. 变更管理

#### 2.1 查看所有未提交变更

**接口：** `GET /changes`

**描述：** 获取仓库中所有未提交的变更记录。

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| deployCode | String | ✅ | 部署编码 |
| spaceCode | String | ✅ | 空间编码 |

**请求示例：**
```
GET /api/v1/files/changes?deployCode=project-001&spaceCode=repo-main
```

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 1,
      "filePath": "src/config.yml",
      "changeType": "ADD",
      "committed": 0,
      "operatorId": 1001,
      "createDate": "2026-06-09T15:30:00"
    },
    {
      "id": 2,
      "filePath": "docs/readme.md",
      "changeType": "MODIFY",
      "committed": 0,
      "operatorId": 1002,
      "createDate": "2026-06-09T15:35:00"
    }
  ]
}
```

---

#### 2.2 分页查看未提交变更（树状结构）

**接口：** `GET /changes/page`

**描述：** 分页获取未提交变更，按目录树状结构排序，文件夹优先显示。

**请求参数：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| deployCode | String | ✅ | - | 部署编码 |
| spaceCode | String | ✅ | - | 空间编码 |
| pageNum | Integer | ❌ | 1 | 页码（从1开始） |
| pageSize | Integer | ❌ | 20 | 每页大小 |
| parentPath | String | ❌ | null | 父目录路径（用于浏览特定目录） |

**请求示例：**
```
GET /api/v1/files/changes/page?deployCode=project-001&spaceCode=repo-main&pageNum=1&pageSize=20&parentPath=src
```

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "records": [
      {
        "id": 1,
        "name": "utils",
        "filePath": "src/utils",
        "isFolder": true,
        "changeType": "ADD",
        "children": [
          {
            "id": 2,
            "name": "helper.js",
            "filePath": "src/utils/helper.js",
            "isFolder": false,
            "changeType": "ADD"
          }
        ]
      },
      {
        "id": 3,
        "name": "config.yml",
        "filePath": "src/config.yml",
        "isFolder": false,
        "changeType": "MODIFY"
      }
    ],
    "total": 2,
    "pageNum": 1,
    "pageSize": 20,
    "totalPages": 1
  }
}
```

---

#### 2.3 提交并推送变更

**接口：** `POST /commit`

**描述：** 将选中的变更记录提交到 Git（可选推送到远程仓库）。

**请求体：**
```json
{
  "deployCode": "project-001",
  "spaceCode": "repo-main",
  "operator": "zhangsan",
  "commitMessage": "Update configuration files",
  "selectedChangeIds": [1, 2, 3],
  "pushToRemote": false
}
```

**参数说明：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| deployCode | String | ✅ | - | 部署编码 |
| spaceCode | String | ✅ | - | 空间编码 |
| operator | String | ✅ | - | 操作人用户名 |
| commitMessage | String | ❌ | 自动生成 | Git 提交信息 |
| selectedChangeIds | List<Long> | ❌ | null | 选中的变更 ID 列表，为空则提交全部 |
| pushToRemote | Boolean | ❌ | false | 是否推送到远程仓库 |

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": "a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0"
}
```
*返回值为 Git Commit Hash*

**错误场景：**
- 无待提交变更：`"No pending changes to commit"`
- Git 冲突：相关错误提示

---

### 3. 查询接口

#### 3.1 获取文件列表

**接口：** `GET /list`

**描述：** 获取指定目录下的文件和文件夹列表。

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| deployCode | String | ✅ | 部署编码 |
| spaceCode | String | ✅ | 空间编码 |
| parentPath | String | ❌ | 父目录路径（为空则返回根目录） |

**请求示例：**
```
GET /api/v1/files/list?deployCode=project-001&spaceCode=repo-main&parentPath=src
```

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 1,
      "deployCode": "project-001",
      "spaceCode": "repo-main",
      "filePath": "src/config.yml",
      "fileType": 1,
      "fileSize": 1024,
      "version": 5,
      "lastCommitId": "abc123..."
    }
  ]
}
```

---

#### 3.2 获取文件历史

**接口：** `GET /history`

**描述：** 获取文件的 Git 提交历史记录。

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| deployCode | String | ✅ | 部署编码 |
| spaceCode | String | ✅ | 空间编码 |
| filePath | String | ✅ | 文件路径 |

**请求示例：**
```
GET /api/v1/files/history?deployCode=project-001&spaceCode=repo-main&filePath=src/config.yml
```

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "commitHash": "a1b2c3d...",
      "author": "zhangsan",
      "email": "zhangsan@example.com",
      "message": "Update config",
      "commitTime": "2026-06-09T15:30:00"
    }
  ]
}
```

---

#### 3.3 读取文件内容

**接口：** `GET /content`

**描述：** 读取文件的当前内容或指定 commit 的内容。

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| deployCode | String | ✅ | 部署编码 |
| spaceCode | String | ✅ | 空间编码 |
| filePath | String | ✅ | 文件路径 |
| commitHash | String | ❌ | Git Commit Hash（为空则读取工作区内容） |

**请求示例：**
```
GET /api/v1/files/content?deployCode=project-001&spaceCode=repo-main&filePath=src/config.yml
```

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": "key: value\nanother_key: another_value"
}
```

---

### 4. 编辑锁管理

#### 4.1 获取文件编辑锁

**接口：** `POST /lock/acquire`

**描述：** 用户打开文件编辑时调用，获取独占编辑权。其他用户在锁释放前无法编辑该文件。

**请求体：**
```json
{
  "deployCode": "project-001",
  "spaceCode": "repo-main",
  "filePath": "src/config.yml",
  "operator": "zhangsan",
  "expireSeconds": 300
}
```

**参数说明：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| deployCode | String | ✅ | - | 部署编码 |
| spaceCode | String | ✅ | - | 空间编码 |
| filePath | String | ✅ | - | 文件路径 |
| operator | String | ✅ | - | 操作人用户名 |
| userId | Long | ❌ | 自动查找 | 用户ID（与 operator 二选一） |
| expireSeconds | Integer | ❌ | 300 | 锁过期时间（秒），默认5分钟 |

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "deployCode": "project-001",
    "spaceCode": "repo-main",
    "filePath": "src/config.yml",
    "userId": 1001,
    "lockToken": "a3f2b8c1-4d5e-6f7g-8h9i-0j1k2l3m4n5o",
    "expireTime": "2026-06-09T15:35:00",
    "createDate": "2026-06-09T15:30:00"
  }
}
```

**错误场景：**
- 文件已被锁定：`"File 'config.yml' is already locked by user 'lisi'"`

---

#### 4.2 释放文件编辑锁

**接口：** `POST /lock/release`

**描述：** 用户保存完成或取消编辑时调用，释放编辑锁。

**请求体：**
```json
{
  "deployCode": "project-001",
  "spaceCode": "repo-main",
  "filePath": "src/config.yml",
  "operator": "zhangsan"
}
```

**参数说明：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| deployCode | String | ✅ | 部署编码 |
| spaceCode | String | ✅ | 空间编码 |
| filePath | String | ✅ | 文件路径 |
| operator | String | ✅ | 操作人用户名 |
| userId | Long | ❌ | 用户ID（与 operator 二选一） |

**响应示例：**
```json
{
  "code": 200,
  "message": "success",
  "data": null
}
```

**错误场景：**
- 不是锁的持有者：`"You do not own the lock on file 'config.yml'"`

---

#### 4.3 查询文件编辑锁状态

**接口：** `GET /lock/status`

**描述：** 查询文件是否被锁定，以及锁的详细信息。

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| deployCode | String | ✅ | 部署编码 |
| spaceCode | String | ✅ | 空间编码 |
| filePath | String | ✅ | 文件路径 |

**请求示例：**
```
GET /api/v1/files/lock/status?deployCode=project-001&spaceCode=repo-main&filePath=src/config.yml
```

**响应示例（已锁定）：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "locked": true,
    "lockedBy": "zhangsan",
    "lockedAt": "2026-06-09T15:30:00",
    "expireTime": "2026-06-09T15:35:00",
    "lockToken": "a3f2b8c1-4d5e-6f7g-8h9i-0j1k2l3m4n5o"
  }
}
```

**响应示例（未锁定）：**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "locked": false
  }
}
```

---

## 使用场景示例

### 场景 1：快速批量更新（脚本化操作）

```javascript
// 不需要编辑锁，依靠乐观锁防冲突
async function batchUpdate() {
  const files = [
    { path: 'config1.yml', content: '...' },
    { path: 'config2.yml', content: '...' }
  ];

  for (const file of files) {
    // 1. 读取文件获取版本号
    const readResp = await fetch(`/api/v1/files/content?filePath=${file.path}`);
    const version = readResp.headers.get('X-File-Version');

    // 2. 更新文件
    await fetch('/api/v1/files/update', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        filePath: file.path,
        content: file.content,
        operator: 'script-bot',
        expectedVersion: parseInt(version),
        checkEditLock: false  // 不检查编辑锁
      })
    });
  }
}
```

---

### 场景 2：在线编辑器（协作编辑）

```javascript
class CollaborativeEditor {
  constructor(config) {
    this.deployCode = config.deployCode;
    this.spaceCode = config.spaceCode;
    this.operator = config.operator;
    this.filePath = null;
    this.currentVersion = null;
    this.hasEditLock = false;
  }

  // 打开文件
  async openFile(filePath) {
    this.filePath = filePath;
    
    // 1. 读取文件内容和版本号
    const contentResp = await fetch(
      `/api/v1/files/content?deployCode=${this.deployCode}&spaceCode=${this.spaceCode}&filePath=${filePath}`
    );
    const contentData = await contentResp.json();
    this.currentVersion = contentData.version;
    
    // 2. 尝试获取编辑锁（非阻塞）
    try {
      await fetch('/api/v1/files/lock/acquire', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          deployCode: this.deployCode,
          spaceCode: this.spaceCode,
          filePath: filePath,
          operator: this.operator,
          expireSeconds: 300
        })
      });
      this.hasEditLock = true;
      console.log('✅ 已获得编辑锁');
    } catch (e) {
      console.warn('⚠️ 文件可能被他人编辑');
      this.hasEditLock = false;
    }
    
    return contentData.data;
  }

  // 保存文件
  async saveFile(content) {
    try {
      const response = await fetch('/api/v1/files/update', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          deployCode: this.deployCode,
          spaceCode: this.spaceCode,
          filePath: this.filePath,
          content: content,
          operator: this.operator,
          expectedVersion: this.currentVersion,
          checkEditLock: !this.hasEditLock  // 如果没有锁，则检查他人的锁
        })
      });

      if (!response.ok) {
        const error = await response.json();
        
        if (error.message?.includes('being edited')) {
          alert('❌ 文件正在被他人编辑，请稍后再试');
          throw new Error('FILE_LOCKED');
        } else if (error.code === 409) {
          alert('⚠️ 文件已被其他人修改，请重新加载');
          await this.reloadFile();
          throw new Error('VERSION_CONFLICT');
        }
      }

      const result = await response.json();
      this.currentVersion++;
      console.log('✅ 保存成功');
      return result;

    } catch (error) {
      console.error('保存失败:', error);
      throw error;
    }
  }

  // 关闭文件
  async closeFile() {
    if (this.hasEditLock && this.filePath) {
      try {
        await fetch('/api/v1/files/lock/release', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            deployCode: this.deployCode,
            spaceCode: this.spaceCode,
            filePath: this.filePath,
            operator: this.operator
          })
        });
        console.log('✅ 已释放编辑锁');
      } catch (e) {
        console.warn('释放锁失败:', e);
      }
    }
    
    this.filePath = null;
    this.currentVersion = null;
    this.hasEditLock = false;
  }

  // 重新加载文件
  async reloadFile() {
    if (this.filePath) {
      return await this.openFile(this.filePath);
    }
  }
}

// 使用示例
const editor = new CollaborativeEditor({
  deployCode: 'project-001',
  spaceCode: 'repo-main',
  operator: 'zhangsan'
});

// 打开 → 编辑 → 保存 → 关闭
const content = await editor.openFile('src/config.yml');
const newContent = content.replace('old', 'new');
await editor.saveFile(newContent);
await editor.closeFile();
```

---

### 场景 3：提交变更到 Git

```javascript
async function commitChanges() {
  // 1. 查看所有未提交变更
  const changesResp = await fetch(
    '/api/v1/files/changes?deployCode=project-001&spaceCode=repo-main'
  );
  const changes = await changesResp.json();
  console.log('待提交变更:', changes.data);

  // 2. 用户选择要提交的变更ID
  const selectedIds = changes.data.map(c => c.id); // 或者让用户勾选

  // 3. 提交到 Git
  const commitResp = await fetch('/api/v1/files/commit', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      deployCode: 'project-001',
      spaceCode: 'repo-main',
      operator: 'zhangsan',
      commitMessage: 'Update configuration files',
      selectedChangeIds: selectedIds,
      pushToRemote: true  // 同时推送到远程
    })
  });

  const result = await commitResp.json();
  console.log('Commit Hash:', result.data);
}
```

---

## 错误处理

### 常见错误码

| 错误码 | 说明 | 处理建议 |
|--------|------|---------|
| 200 | 成功 | - |
| 409 | 冲突 | 检查版本冲突、文件锁定、文件已存在等 |
| 500 | 服务器错误 | 联系管理员，查看日志 |

### 错误响应示例

**版本冲突：**
```json
{
  "code": 409,
  "message": "File conflict detected: file 'config.yml' has been modified by another user (expected version 5, current version 6). Please reload the file and retry.",
  "data": null
}
```

**文件被锁定：**
```json
{
  "code": 409,
  "message": "File 'config.yml' is currently being edited by user 'lisi'. Please wait until they finish or the lock expires.",
  "data": null
}
```

**文件已存在：**
```json
{
  "code": 409,
  "message": "File already exists: src/config.yml",
  "data": null
}
```

---

## 最佳实践

### 1. 始终提供 `expectedVersion`

```javascript
// ✅ 推荐
await updateFile({
  filePath: 'config.yml',
  content: '...',
  expectedVersion: 5  // 从读取操作中获得
});

// ❌ 不推荐：可能导致意外覆盖
await updateFile({
  filePath: 'config.yml',
  content: '...'
  // 缺少 expectedVersion
});
```

### 2. 根据场景选择 `checkEditLock`

```javascript
// 场景 A：批量脚本操作
checkEditLock: false  // 高性能

// 场景 B：在线编辑器
checkEditLock: !hasEditLock  // 智能判断

// 场景 C：重要文件删除
checkEditLock: true  // 安全优先
```

### 3. 及时释放编辑锁

```javascript
// ✅ 推荐：使用 try-finally
try {
  await acquireLock();
  // 编辑操作...
} finally {
  await releaseLock();  // 确保释放
}

// ❌ 不推荐：可能忘记释放
await acquireLock();
// 编辑操作...
// 如果这里抛出异常，锁不会释放
```

### 4. 合理的锁超时时间

```javascript
// 短时间编辑：60-120 秒
expireSeconds: 60

// 正常编辑：300 秒（5分钟）
expireSeconds: 300

// 长时间编辑：600 秒（10分钟）+ 前端定时续期
expireSeconds: 600
```

### 5. 错误重试策略

```javascript
async function updateWithRetry(filePath, content, version, maxRetries = 3) {
  for (let i = 0; i < maxRetries; i++) {
    try {
      return await updateFile({
        filePath,
        content,
        expectedVersion: version
      });
    } catch (error) {
      if (error.message.includes('conflict') && i < maxRetries - 1) {
        // 版本冲突，重新读取后重试
        const newData = await readFile(filePath);
        version = newData.version;
        continue;
      }
      throw error;
    }
  }
}
```

### 6. 批量操作优化

```javascript
// ✅ 推荐：批量提交
const changeIds = [1, 2, 3, 4, 5];
await commitChanges({
  selectedChangeIds: changeIds,
  commitMessage: 'Batch update'
});

// ❌ 不推荐：逐个提交
for (const id of changeIds) {
  await commitChanges({
    selectedChangeIds: [id],
    commitMessage: 'Update'
  });
}
```

---

## 附录

### 数据模型

#### FileChange（变更记录）
```typescript
interface FileChange {
  id: number;
  deployCode: string;
  spaceCode: string;
  filePath: string;
  changeType: 'ADD' | 'MODIFY' | 'DELETE';
  newPath?: string;
  committed: 0 | 1;
  commitId?: string;
  operatorId: number;
  createDate: string;
  committedDate?: string;
}
```

#### FileIndex（文件索引）
```typescript
interface FileIndex {
  id: number;
  deployCode: string;
  spaceCode: string;
  filePath: string;
  fileType: 1 | 2;  // 1-文件, 2-文件夹
  fileSize: number;
  version: number;
  lastCommitId?: string;
  lockedBy?: number;
  lockedAt?: string;
  isDeleted: 0 | 1;
}
```

#### FileLock（文件锁）
```typescript
interface FileLock {
  id: number;
  deployCode: string;
  spaceCode: string;
  filePath: string;
  userId: number;
  lockToken: string;
  expireTime: string;
  createDate: string;
}
```

---

**文档版本：** v1.0  
**最后更新：** 2026-06-09  
**维护者：** Repository Core Service Team

