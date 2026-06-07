package com.example.repo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.repo.dto.CommitRequest;
import com.example.repo.dto.FileLockRequest;
import com.example.repo.dto.FileOperationRequest;
import com.example.repo.entity.FileChange;
import com.example.repo.entity.FileIndex;
import com.example.repo.entity.FileLock;
import com.example.repo.entity.User;
import com.example.repo.exception.ConflictException;
import com.example.repo.git.GitService;
import com.example.repo.lock.DistributedLockManager;
import com.example.repo.mapper.FileChangeMapper;
import com.example.repo.mapper.FileIndexMapper;
import com.example.repo.mapper.FileLockMapper;
import com.example.repo.mapper.UserMapper;
import com.example.repo.nas.NasHealthChecker;
import org.redisson.api.RLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文件协作服务 - 实现三级锁机制
 * 工作流：创建/更新/删除（仅写磁盘+记录变更）→ 查看变更 → 用户勾选提交推送
 */
@Service
public class FileCollaborationService {

    private static final int FILE_TYPE_FILE = 1;
    private static final int FILE_TYPE_FOLDER = 2;

    @Autowired
    private DistributedLockManager lockManager;

    @Autowired
    private GitService gitService;

    @Autowired
    private FileIndexMapper fileIndexMapper;

    @Autowired
    private FileLockMapper fileLockMapper;

    @Autowired
    private FileChangeMapper fileChangeMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private NasHealthChecker nasHealthChecker;

    // ======================== 创建 ========================

    /**
     * 创建文件或文件夹
     * 仅写入磁盘 + 更新索引 + 记录变更，不执行 git commit
     */
    @Transactional
    public FileChange createFileOrFolder(FileOperationRequest request) {
        if (!nasHealthChecker.isHealthy()) {
            throw new RuntimeException("NAS storage is currently unavailable");
        }

        Long repoId = request.getRepoId();
        String filePath = request.getFilePath();
        String operator = request.getOperator();
        int fileType = request.getFileType() != null ? request.getFileType() : FILE_TYPE_FILE;

        User user = findUserByUsername(operator);

        FileIndex existing = findFileIndex(repoId, filePath);
        if (existing != null) {
            throw new RuntimeException((fileType == FILE_TYPE_FOLDER ? "Folder" : "File")
                    + " already exists: " + filePath);
        }

        RLock fileLock = lockManager.tryGetFileLock(repoId, filePath, 3, 30);
        if (fileLock == null) {
            throw new RuntimeException("Operation is currently in progress by another user");
        }

        try {
            RLock repoLock = lockManager.tryGetRepoLock(repoId, 5, 60);
            if (repoLock == null) {
                throw new RuntimeException("Repository is currently busy, please try again later");
            }

            try {
                if (fileType == FILE_TYPE_FOLDER) {
                    gitService.createFolder(repoId, filePath);
                    createFileIndex(repoId, filePath, FILE_TYPE_FOLDER, 0L, user.getId());
                } else {
                    String content = request.getContent() != null ? request.getContent() : "";
                    gitService.writeFileContent(repoId, filePath, content);
                    createFileIndex(repoId, filePath, FILE_TYPE_FILE,
                            (long) content.getBytes().length, user.getId());
                }

                // 记录变更（不提交）
                return recordChange(repoId, filePath, "ADD", null, user.getId());

            } finally {
                lockManager.releaseLock(repoLock);
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create: " + e.getMessage(), e);
        } finally {
            lockManager.releaseLock(fileLock);
        }
    }

    // ======================== 更新 ========================

    /**
     * 更新文件或文件夹
     * 仅写入磁盘 + 更新索引 + 记录变更，不执行 git commit
     */
    @Transactional
    public FileChange updateFileOrFolder(FileOperationRequest request) {
        if (!nasHealthChecker.isHealthy()) {
            throw new RuntimeException("NAS storage is currently unavailable");
        }

        Long repoId = request.getRepoId();
        String filePath = request.getFilePath();
        String operator = request.getOperator();

        User user = findUserByUsername(operator);

        FileIndex fileIndex = findFileIndex(repoId, filePath);
        if (fileIndex == null) {
            throw new RuntimeException("File or folder not found: " + filePath);
        }

        int fileType = fileIndex.getFileType();

        // 乐观锁冲突检测：校验客户端持有的版本号与当前版本是否一致
        Integer expectedVersion = request.getExpectedVersion();
        if (expectedVersion != null && !expectedVersion.equals(fileIndex.getVersion())) {
            throw new ConflictException(
                    "File conflict detected: file '" + filePath + "' has been modified by another user " +
                    "(expected version " + expectedVersion + ", current version " + fileIndex.getVersion() + "). " +
                    "Please reload the file and retry.");
        }

        // 编辑锁校验：检查是否有其他用户正在编辑此文件
        FileLock editLock = findActiveEditLock(repoId, filePath);
        if (editLock != null && !editLock.getUserId().equals(user.getId())) {
            User lockOwner = userMapper.selectById(editLock.getUserId());
            String lockOwnerName = lockOwner != null ? lockOwner.getUsername() : "unknown";
            throw new RuntimeException(
                    "File '" + filePath + "' is currently being edited by user '" + lockOwnerName + "'. " +
                    "Please wait until they finish or the lock expires.");
        }

        RLock fileLock = lockManager.tryGetFileLock(repoId, filePath, 3, 30);
        if (fileLock == null) {
            throw new RuntimeException("File or folder is currently locked by another user");
        }

        try {
            RLock repoLock = lockManager.tryGetRepoLock(repoId, 5, 60);
            if (repoLock == null) {
                throw new RuntimeException("Repository is currently busy, please try again later");
            }

            try {
                if (fileType == FILE_TYPE_FOLDER) {
                    return updateFolder(repoId, filePath, request, user);
                } else {
                    return updateFileContent(repoId, filePath, request, user, fileIndex);
                }

            } finally {
                lockManager.releaseLock(repoLock);
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to update: " + e.getMessage(), e);
        } finally {
            lockManager.releaseLock(fileLock);
        }
    }

    /**
     * 更新文件内容（不提交）
     */
    private FileChange updateFileContent(Long repoId, String filePath,
                                         FileOperationRequest request,
                                         User user, FileIndex fileIndex) throws Exception {
        String content = request.getContent();
        String newPath = request.getNewPath();

        if (content == null && newPath == null) {
            throw new RuntimeException("Either content or newPath must be provided for file update");
        }

        String changeType = "MODIFY";
        String recordedNewPath = null;

        if (newPath != null && !newPath.equals(filePath)) {
            // 重命名/移动文件
            gitService.renameFile(repoId, filePath, newPath);
            fileIndex.setFilePath(newPath);
            recordedNewPath = newPath;

            // 同时更新内容
            if (content != null) {
                gitService.writeFileContent(repoId, newPath, content);
                fileIndex.setFileSize((long) content.getBytes().length);
            }
        } else {
            // 仅更新内容
            if (content == null) {
                throw new RuntimeException("Content cannot be null for file update without rename");
            }
            gitService.writeFileContent(repoId, filePath, content);
            fileIndex.setFileSize((long) content.getBytes().length);
        }

        fileIndex.setVersion(fileIndex.getVersion() + 1);
        fileIndex.setUpdatedBy(user.getId());
        fileIndex.setUpdatedAt(LocalDateTime.now());
        fileIndexMapper.updateById(fileIndex);

        String recordPath = (recordedNewPath != null) ? filePath : filePath;
        return recordChange(repoId, recordPath, changeType, recordedNewPath, user.getId());
    }

    /**
     * 更新文件夹（重命名/移动，不提交）
     */
    private FileChange updateFolder(Long repoId, String folderPath,
                                    FileOperationRequest request,
                                    User user) throws Exception {
        String newPath = request.getNewPath();
        if (newPath == null || newPath.isEmpty()) {
            throw new RuntimeException("newPath is required for folder update (rename/move)");
        }
        if (newPath.equals(folderPath)) {
            throw new RuntimeException("New path must be different from current path");
        }

        FileIndex existingTarget = findFileIndex(repoId, newPath);
        if (existingTarget != null) {
            throw new RuntimeException("Target path already exists: " + newPath);
        }

        List<String[]> affectedFiles = gitService.renameFolder(repoId, folderPath, newPath);

        // 更新文件夹本身的索引
        FileIndex folderIndex = findFileIndex(repoId, folderPath);
        if (folderIndex != null) {
            folderIndex.setFilePath(newPath);
            folderIndex.setVersion(folderIndex.getVersion() + 1);
            folderIndex.setUpdatedBy(user.getId());
            folderIndex.setUpdatedAt(LocalDateTime.now());
            fileIndexMapper.updateById(folderIndex);
        }

        // 更新子项索引
        for (String[] pair : affectedFiles) {
            if (pair[0].endsWith(".gitkeep")) continue;
            FileIndex childIndex = findFileIndex(repoId, pair[0]);
            if (childIndex != null) {
                childIndex.setFilePath(pair[1]);
                childIndex.setVersion(childIndex.getVersion() + 1);
                childIndex.setUpdatedBy(user.getId());
                childIndex.setUpdatedAt(LocalDateTime.now());
                fileIndexMapper.updateById(childIndex);
            }
        }

        return recordChange(repoId, folderPath, "MODIFY", newPath, user.getId());
    }

    // ======================== 删除 ========================

    /**
     * 删除文件或文件夹
     * 仅删除磁盘文件 + 标记索引 + 记录变更，不执行 git commit
     */
    @Transactional
    public FileChange deleteFileOrFolder(Long repoId, String filePath, String operator) {
        if (!nasHealthChecker.isHealthy()) {
            throw new RuntimeException("NAS storage is currently unavailable");
        }

        User user = findUserByUsername(operator);

        FileIndex fileIndex = findFileIndex(repoId, filePath);
        if (fileIndex == null) {
            throw new RuntimeException("File or folder not found: " + filePath);
        }

        int fileType = fileIndex.getFileType();

        // 编辑锁校验：检查是否有其他用户正在编辑此文件
        FileLock editLock = findActiveEditLock(repoId, filePath);
        if (editLock != null && !editLock.getUserId().equals(user.getId())) {
            User lockOwner = userMapper.selectById(editLock.getUserId());
            String lockOwnerName = lockOwner != null ? lockOwner.getUsername() : "unknown";
            throw new RuntimeException(
                    "File '" + filePath + "' is currently being edited by user '" + lockOwnerName + "'. " +
                    "Please wait until they finish or the lock expires.");
        }

        RLock fileLock = lockManager.tryGetFileLock(repoId, filePath, 3, 30);
        if (fileLock == null) {
            throw new RuntimeException("File or folder is currently locked by another user");
        }

        try {
            RLock repoLock = lockManager.tryGetRepoLock(repoId, 5, 60);
            if (repoLock == null) {
                throw new RuntimeException("Repository is currently busy, please try again later");
            }

            try {
                if (fileType == FILE_TYPE_FOLDER) {
                    deleteFolderPhysical(repoId, user, filePath);
                } else {
                    deleteFilePhysical(repoId, filePath);
                }

                // 标记为已删除
                fileIndex.setIsDeleted(1);
                fileIndex.setVersion(fileIndex.getVersion() + 1);
                fileIndex.setUpdatedBy(user.getId());
                fileIndex.setUpdatedAt(LocalDateTime.now());
                fileIndexMapper.updateById(fileIndex);

                // 文件夹子项也标记删除
                if (fileType == FILE_TYPE_FOLDER) {
                    markChildrenDeleted(repoId, filePath, user.getId());
                }

                return recordChange(repoId, filePath, "DELETE", null, user.getId());

            } finally {
                lockManager.releaseLock(repoLock);
            }

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete: " + e.getMessage(), e);
        } finally {
            lockManager.releaseLock(fileLock);
        }
    }

    /** 物理删除单个文件 */
    private void deleteFilePhysical(Long repoId, String filePath) throws Exception {
        java.io.File file = new java.io.File(gitService.getRepoPath(repoId), filePath);
        if (file.exists()) {
            file.delete();
        }
    }

    /** 物理删除文件夹及其下所有文件 */
    private void deleteFolderPhysical(Long repoId, User user, String folderPath) throws Exception {
        gitService.deleteFolder(repoId, folderPath);
    }

    /** 将文件夹子项索引标记为已删除 */
    private void markChildrenDeleted(Long repoId, String folderPath, Long userId) {
        List<FileIndex> children = fileIndexMapper.selectList(
                new LambdaQueryWrapper<FileIndex>()
                        .eq(FileIndex::getRepoId, repoId)
                        .eq(FileIndex::getIsDeleted, 0)
                        .likeRight(FileIndex::getFilePath, folderPath + "/")
        );
        for (FileIndex child : children) {
            child.setIsDeleted(1);
            child.setVersion(child.getVersion() + 1);
            child.setUpdatedBy(userId);
            child.setUpdatedAt(LocalDateTime.now());
            fileIndexMapper.updateById(child);
        }
    }

    // ======================== 查看变更 ========================

    /**
     * 获取仓库的所有未提交变更
     */
    public List<FileChange> getPendingChanges(Long repoId) {
        return fileChangeMapper.selectList(
                new LambdaQueryWrapper<FileChange>()
                        .eq(FileChange::getRepoId, repoId)
                        .eq(FileChange::getCommitted, 0)
                        .orderByDesc(FileChange::getCreatedAt)
        );
    }

    // ======================== 提交推送 ========================

    /**
     * 用户勾选变更后提交并可选推送
     * 流程：git reset → git add 选中文件 → git commit → 可选 git push
     */
    @Transactional
    public String commitAndPush(CommitRequest request) {
        Long repoId = request.getRepoId();
        User user = findUserByUsername(request.getOperator());

        // 提交阶段需要获取仓库级锁，防止并发 commit 导致 Git 状态异常
        RLock repoLock = lockManager.tryGetRepoLock(repoId, 10, 120);
        if (repoLock == null) {
            throw new RuntimeException("Repository is currently busy with another commit, please try again later");
        }

        try {

        // 查询待提交的变更
        List<FileChange> changes;
        if (request.getSelectedChangeIds() != null && !request.getSelectedChangeIds().isEmpty()) {
            changes = fileChangeMapper.selectList(
                    new LambdaQueryWrapper<FileChange>()
                            .eq(FileChange::getRepoId, repoId)
                            .eq(FileChange::getCommitted, 0)
                            .in(FileChange::getId, request.getSelectedChangeIds())
            );
        } else {
            changes = getPendingChanges(repoId);
        }

        if (changes.isEmpty()) {
            throw new RuntimeException("No pending changes to commit");
        }

        try {
            // 1. 重置暂存区（避免混入非预期的已暂存文件）
            gitService.resetStaged(repoId);

            // 2. 按变更类型分别暂存
            List<String> addPaths = new ArrayList<>();
            List<String> rmPaths = new ArrayList<>();

            for (FileChange change : changes) {
                switch (change.getChangeType()) {
                    case "ADD", "MODIFY" -> {
                        addPaths.add(change.getFilePath());
                        if (change.getNewPath() != null) {
                            // 重命名：旧路径 rm，新路径 add
                            rmPaths.add(change.getFilePath());
                            addPaths.add(change.getNewPath());
                            addPaths.remove(change.getFilePath());
                        }
                    }
                    case "DELETE" -> rmPaths.add(change.getFilePath());
                }
            }

            if (!addPaths.isEmpty()) {
                gitService.addFiles(repoId, addPaths);
            }
            if (!rmPaths.isEmpty()) {
                gitService.rmFiles(repoId, rmPaths);
            }

            // 3. 提交
            String commitMsg = request.getCommitMessage();
            if (commitMsg == null || commitMsg.isEmpty()) {
                commitMsg = "Commit " + changes.size() + " change(s)";
            }

            String commitHash = gitService.commitStaged(repoId, commitMsg,
                    getAuthorName(user), getAuthorEmail(user));

            // 4. 标记变更为已提交
            LocalDateTime now = LocalDateTime.now();
            for (FileChange change : changes) {
                change.setCommitted(1);
                change.setCommitId(commitHash);
                change.setCommittedAt(now);
                fileChangeMapper.updateById(change);
            }

            // 5. 更新 FileIndex 的 lastCommitId
            updateFileIndexCommitId(repoId, changes, commitHash);

            // 6. 可选推送
            if (Boolean.TRUE.equals(request.getPushToRemote())) {
                gitService.push(repoId);
            }

            return commitHash;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to commit: " + e.getMessage(), e);
        }

        } finally {
            lockManager.releaseLock(repoLock);
        }
    }

    /** 提交后更新相关 FileIndex 的 lastCommitId */
    private void updateFileIndexCommitId(Long repoId, List<FileChange> changes, String commitHash) {
        for (FileChange change : changes) {
            String targetPath = change.getNewPath() != null ? change.getNewPath() : change.getFilePath();
            FileIndex idx = findFileIndex(repoId, targetPath);
            if (idx == null && "DELETE".equals(change.getChangeType())) {
                // 已删除的文件查找（包含已标记删除的）
                idx = fileIndexMapper.selectOne(
                        new LambdaQueryWrapper<FileIndex>()
                                .eq(FileIndex::getRepoId, repoId)
                                .eq(FileIndex::getFilePath, change.getFilePath())
                                .orderByDesc(FileIndex::getId)
                                .last("LIMIT 1")
                );
            }
            if (idx != null) {
                idx.setLastCommitId(commitHash);
                fileIndexMapper.updateById(idx);
            }
        }
    }

    // ======================== 编辑锁管理 ========================

    /**
     * 获取文件编辑锁
     * 用户打开文件编辑时调用，其他用户在锁释放前无法编辑该文件
     */
    @Transactional
    public FileLock acquireEditLock(FileLockRequest request) {
        Long repoId = request.getRepoId();
        String filePath = request.getFilePath();

        Long userId = resolveUserId(request);

        FileIndex fileIndex = findFileIndex(repoId, filePath);
        if (fileIndex == null) {
            throw new RuntimeException("File not found: " + filePath);
        }

        // 检查是否已有其他用户的活跃锁
        FileLock existingLock = findActiveEditLock(repoId, filePath);
        if (existingLock != null && !existingLock.getUserId().equals(userId)) {
            User lockOwner = userMapper.selectById(existingLock.getUserId());
            String lockOwnerName = lockOwner != null ? lockOwner.getUsername() : "unknown";
            throw new RuntimeException(
                    "File '" + filePath + "' is already locked by user '" + lockOwnerName + "'");
        }

        // 如果是同一用户已有锁，续期
        if (existingLock != null && existingLock.getUserId().equals(userId)) {
            int expireSeconds = request.getExpireSeconds() != null ? request.getExpireSeconds() : 300;
            existingLock.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
            fileLockMapper.updateById(existingLock);
            fileIndex.setLockedBy(userId);
            fileIndex.setLockedAt(existingLock.getExpireTime());
            fileIndexMapper.updateById(fileIndex);
            return existingLock;
        }

        // 创建新锁
        String lockToken = UUID.randomUUID().toString();
        int expireSeconds = request.getExpireSeconds() != null ? request.getExpireSeconds() : 300;

        FileLock fileLock = new FileLock();
        fileLock.setRepoId(repoId);
        fileLock.setFilePath(filePath);
        fileLock.setUserId(userId);
        fileLock.setLockToken(lockToken);
        fileLock.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        fileLock.setCreatedAt(LocalDateTime.now());
        fileLockMapper.insert(fileLock);

        // 同步更新 FileIndex 的锁定状态
        fileIndex.setLockedBy(userId);
        fileIndex.setLockedAt(fileLock.getExpireTime());
        fileIndexMapper.updateById(fileIndex);

        return fileLock;
    }

    /**
     * 释放文件编辑锁
     */
    @Transactional
    public void releaseEditLock(FileLockRequest request) {
        Long repoId = request.getRepoId();
        String filePath = request.getFilePath();
        Long userId = resolveUserId(request);

        FileLock lock = findActiveEditLock(repoId, filePath);
        if (lock == null) {
            return;
        }

        if (!lock.getUserId().equals(userId)) {
            throw new RuntimeException("You do not own the lock on file '" + filePath + "'");
        }

        fileLockMapper.deleteById(lock.getId());

        FileIndex fileIndex = findFileIndex(repoId, filePath);
        if (fileIndex != null) {
            fileIndex.setLockedBy(null);
            fileIndex.setLockedAt(null);
            fileIndexMapper.updateById(fileIndex);
        }
    }

    /**
     * 查询文件的编辑锁状态
     */
    public Map<String, Object> getEditLockStatus(Long repoId, String filePath) {
        FileLock lock = findActiveEditLock(repoId, filePath);
        Map<String, Object> result = new HashMap<>();

        if (lock == null) {
            result.put("locked", false);
            return result;
        }

        User lockOwner = userMapper.selectById(lock.getUserId());
        result.put("locked", true);
        result.put("lockedBy", lockOwner != null ? lockOwner.getUsername() : "unknown");
        result.put("lockedAt", lock.getCreatedAt());
        result.put("expireTime", lock.getExpireTime());
        result.put("lockToken", lock.getLockToken());
        return result;
    }

    // ======================== 查询 ========================

    /**
     * 获取文件/文件夹列表
     */
    public List<FileIndex> getFileList(Long repoId, String parentPath) {
        LambdaQueryWrapper<FileIndex> wrapper = new LambdaQueryWrapper<FileIndex>()
                .eq(FileIndex::getRepoId, repoId)
                .eq(FileIndex::getIsDeleted, 0);

        if (parentPath != null && !parentPath.isEmpty()) {
            wrapper.likeRight(FileIndex::getFilePath, parentPath + "/");
        }

        return fileIndexMapper.selectList(wrapper);
    }

    /**
     * 获取文件历史
     */
    public List<GitService.CommitInfo> getFileHistory(Long repoId, String filePath) {
        try {
            return gitService.getFileHistory(repoId, filePath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get file history: " + e.getMessage(), e);
        }
    }

    // ======================== 私有工具方法 ========================

    /** 记录一条文件变更 */
    private FileChange recordChange(Long repoId, String filePath,
                                    String changeType, String newPath, Long operatorId) {
        FileChange change = new FileChange();
        change.setRepoId(repoId);
        change.setFilePath(filePath);
        change.setChangeType(changeType);
        change.setNewPath(newPath);
        change.setCommitted(0);
        change.setOperatorId(operatorId);
        change.setCreatedAt(LocalDateTime.now());
        fileChangeMapper.insert(change);
        return change;
    }

    private User findUserByUsername(String username) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username)
        );
        if (user == null) {
            throw new RuntimeException("User not found: " + username);
        }
        return user;
    }

    private FileIndex findFileIndex(Long repoId, String filePath) {
        return fileIndexMapper.selectOne(
                new LambdaQueryWrapper<FileIndex>()
                        .eq(FileIndex::getRepoId, repoId)
                        .eq(FileIndex::getFilePath, filePath)
                        .eq(FileIndex::getIsDeleted, 0)
        );
    }

    private void createFileIndex(Long repoId, String filePath, int fileType,
                                 Long fileSize, Long userId) {
        FileIndex index = new FileIndex();
        index.setRepoId(repoId);
        index.setFilePath(filePath);
        index.setFileType(fileType);
        index.setFileSize(fileSize);
        index.setVersion(1);
        index.setIsDeleted(0);
        index.setCreatedBy(userId);
        index.setCreatedAt(LocalDateTime.now());
        fileIndexMapper.insert(index);
    }

    /** 查找文件的活跃编辑锁（未过期） */
    private FileLock findActiveEditLock(Long repoId, String filePath) {
        FileLock lock = fileLockMapper.selectOne(
                new LambdaQueryWrapper<FileLock>()
                        .eq(FileLock::getRepoId, repoId)
                        .eq(FileLock::getFilePath, filePath)
                        .gt(FileLock::getExpireTime, LocalDateTime.now())
                        .orderByDesc(FileLock::getCreatedAt)
                        .last("LIMIT 1")
        );
        return lock;
    }

    /** 解析用户 ID（优先使用 operator，其次使用 userId） */
    private Long resolveUserId(FileLockRequest request) {
        if (request.getOperator() != null && !request.getOperator().isEmpty()) {
            User user = findUserByUsername(request.getOperator());
            return user.getId();
        }
        if (request.getUserId() != null) {
            return request.getUserId();
        }
        throw new RuntimeException("Either 'operator' or 'userId' must be provided");
    }

    private String getAuthorName(User user) {
        return user.getFullName() != null ? user.getFullName() : user.getUsername();
    }

    private String getAuthorEmail(User user) {
        return user.getEmail() != null ? user.getEmail() : user.getUsername() + "@local";
    }
}
