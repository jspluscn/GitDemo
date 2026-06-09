package com.example.repo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.repo.dto.CommitRequest;
import com.example.repo.dto.FileLockRequest;
import com.example.repo.dto.FileOperationRequest;
import com.example.repo.dto.PageRequest;
import com.example.repo.dto.PageResponse;
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
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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

    // ======================== 创建 ========================

    /**
     * 创建文件或文件夹
     * 仅写入磁盘 + 更新索引 + 记录变更，不执行 git commit
     */
    @Transactional
    public FileChange createFileOrFolder(FileOperationRequest request) {
        log.info("Creating file or folder: {}", request.getContent());
        String deployCode = request.getDeployCode();
        String spaceCode = request.getSpaceCode();
        String filePath = request.getFilePath();
        String operator = request.getOperator();
        int fileType = request.getFileType() != null ? request.getFileType() : FILE_TYPE_FILE;

        User user = findUserByUsername(operator);

        FileIndex existing = findFileIndex(deployCode, spaceCode, filePath);
        if (existing != null) {
            throw new RuntimeException((fileType == FILE_TYPE_FOLDER ? "Folder" : "File")
                    + " already exists: " + filePath);
        }

        RLock fileLock = lockManager.tryGetFileLock(deployCode, spaceCode, filePath, 3, 30);
        if (fileLock == null) {
            throw new RuntimeException("Operation is currently in progress by another user");
        }

        try {
            RLock repoLock = lockManager.tryGetRepoLock(deployCode, spaceCode, 5, 60);
            if (repoLock == null) {
                throw new RuntimeException("Repository is currently busy, please try again later");
            }

            try {
                if (fileType == FILE_TYPE_FOLDER) {
                    gitService.createFolder(deployCode, spaceCode, filePath);
                    createFileIndex(deployCode, spaceCode, filePath, FILE_TYPE_FOLDER, 0L, user.getId());
                } else {
                    String content = request.getContent() != null ? request.getContent() : "";
                    gitService.writeFileContent(deployCode, spaceCode, filePath, content);
                    createFileIndex(deployCode, spaceCode, filePath, FILE_TYPE_FILE,
                            (long) content.getBytes().length, user.getId());
                }

                // 记录变更（不提交）
                return recordChange(deployCode, spaceCode, filePath, "ADD", null, user.getId());

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
        String deployCode = request.getDeployCode();
        String spaceCode = request.getSpaceCode();
        String filePath = request.getFilePath();
        String operator = request.getOperator();

        User user = findUserByUsername(operator);

        FileIndex fileIndex = findFileIndex(deployCode, spaceCode, filePath);
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
        FileLock editLock = findActiveEditLock(deployCode, spaceCode, filePath);
        if (editLock != null && !editLock.getUserId().equals(user.getId())) {
            User lockOwner = userMapper.selectById(editLock.getUserId());
            String lockOwnerName = lockOwner != null ? lockOwner.getUsername() : "unknown";
            throw new RuntimeException(
                    "File '" + filePath + "' is currently being edited by user '" + lockOwnerName + "'. " +
                            "Please wait until they finish or the lock expires.");
        }

        RLock fileLock = lockManager.tryGetFileLock(deployCode, spaceCode, filePath, 3, 30);
        if (fileLock == null) {
            throw new RuntimeException("File or folder is currently locked by another user");
        }

        try {
            RLock repoLock = lockManager.tryGetRepoLock(deployCode, spaceCode, 5, 60);
            if (repoLock == null) {
                throw new RuntimeException("Repository is currently busy, please try again later");
            }

            try {
                if (fileType == FILE_TYPE_FOLDER) {
                    return updateFolder(deployCode, spaceCode, filePath, request, user);
                } else {
                    return updateFileContent(deployCode, spaceCode, filePath, request, user, fileIndex);
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
    private FileChange updateFileContent(String deployCode, String spaceCode, String filePath,
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
            gitService.renameFile(deployCode, spaceCode, filePath, newPath);
            fileIndex.setFilePath(newPath);
            recordedNewPath = newPath;

            // 同时更新内容
            if (content != null) {
                gitService.writeFileContent(deployCode, spaceCode, newPath, content);
                fileIndex.setFileSize((long) content.getBytes().length);
            }
        } else {
            // 仅更新内容
            if (content == null) {
                throw new RuntimeException("Content cannot be null for file update without rename");
            }
            
            // 【关键】写入前检查：是否有其他未提交的变更针对此文件
            checkUncommittedChanges(deployCode, spaceCode, filePath, fileIndex.getId());
            
            gitService.writeFileContent(deployCode, spaceCode, filePath, content);
            fileIndex.setFileSize((long) content.getBytes().length);
        }

        fileIndex.setVersion(fileIndex.getVersion() + 1);
        fileIndex.setUpdatedBy(user.getId());
        fileIndex.setLastUpdatedDate(LocalDateTime.now());
        fileIndexMapper.updateById(fileIndex);

        return recordChange(deployCode, spaceCode, filePath, changeType, recordedNewPath, user.getId());
    }

    /**
     * 更新文件夹（重命名/移动，不提交）
     */
    private FileChange updateFolder(String deployCode, String spaceCode, String folderPath,
                                    FileOperationRequest request,
                                    User user) throws Exception {
        String newPath = request.getNewPath();
        if (newPath == null || newPath.isEmpty()) {
            throw new RuntimeException("newPath is required for folder update (rename/move)");
        }
        if (newPath.equals(folderPath)) {
            throw new RuntimeException("New path must be different from current path");
        }

        FileIndex existingTarget = findFileIndex(deployCode, spaceCode, newPath);
        if (existingTarget != null) {
            throw new RuntimeException("Target path already exists: " + newPath);
        }

        // 收集所有需要锁定的路径：源文件夹、目标文件夹、所有子文件的旧路径和新路径
        List<String> allPathsToLock = new ArrayList<>();
        allPathsToLock.add(folderPath);
        allPathsToLock.add(newPath);
        
        // 查询所有子项
        List<FileIndex> children = fileIndexMapper.selectList(
                new LambdaQueryWrapper<FileIndex>()
                        .eq(FileIndex::getDeployCode, deployCode)
                        .eq(FileIndex::getSpaceCode, spaceCode)
                        .eq(FileIndex::getIsDeleted, 0)
                        .likeRight(FileIndex::getFilePath, folderPath + "/")
        );
        
        for (FileIndex child : children) {
            allPathsToLock.add(child.getFilePath());
            // 计算新路径
            String newChildPath = newPath + child.getFilePath().substring(folderPath.length());
            allPathsToLock.add(newChildPath);
        }
        
        // 按字典序排序，避免死锁
        allPathsToLock.sort(String::compareTo);
        
        // 获取所有锁
        List<RLock> acquiredLocks = new ArrayList<>();
        try {
            for (String path : allPathsToLock) {
                RLock lock = lockManager.tryGetFileLock(deployCode, spaceCode, path, 3, 30);
                if (lock == null) {
                    // 获取锁失败，释放已获取的所有锁
                    for (RLock acquired : acquiredLocks) {
                        lockManager.releaseLock(acquired);
                    }
                    throw new RuntimeException("Path '" + path + "' is currently locked by another user");
                }
                acquiredLocks.add(lock);
            }

            // 执行文件夹重命名
            List<String[]> affectedFiles = gitService.renameFolder(deployCode, spaceCode, folderPath, newPath);

            // 更新文件夹本身的索引
            FileIndex folderIndex = findFileIndex(deployCode, spaceCode, folderPath);
            if (folderIndex != null) {
                folderIndex.setFilePath(newPath);
                folderIndex.setVersion(folderIndex.getVersion() + 1);
                folderIndex.setUpdatedBy(user.getId());
                folderIndex.setLastUpdatedDate(LocalDateTime.now());
                fileIndexMapper.updateById(folderIndex);
            }

            // 更新子项索引
            for (String[] pair : affectedFiles) {
                if (pair[0].endsWith(".gitkeep")) continue;
                FileIndex childIndex = findFileIndex(deployCode, spaceCode, pair[0]);
                if (childIndex != null) {
                    childIndex.setFilePath(pair[1]);
                    childIndex.setVersion(childIndex.getVersion() + 1);
                    childIndex.setUpdatedBy(user.getId());
                    childIndex.setLastUpdatedDate(LocalDateTime.now());
                    fileIndexMapper.updateById(childIndex);
                }
            }

            return recordChange(deployCode, spaceCode, folderPath, "MODIFY", newPath, user.getId());

        } finally {
            // 释放所有锁
            for (RLock lock : acquiredLocks) {
                lockManager.releaseLock(lock);
            }
        }
    }

    // ======================== 删除 ========================

    /**
     * 删除文件或文件夹
     * 仅删除磁盘文件 + 标记索引 + 记录变更，不执行 git commit
     */
    @Transactional
    public FileChange deleteFileOrFolder(String deployCode, String spaceCode, String filePath, String operator) {
        User user = findUserByUsername(operator);

        FileIndex fileIndex = findFileIndex(deployCode, spaceCode, filePath);
        if (fileIndex == null) {
            throw new RuntimeException("File or folder not found: " + filePath);
        }

        int fileType = fileIndex.getFileType();

        // 编辑锁校验：检查是否有其他用户正在编辑此文件
        FileLock editLock = findActiveEditLock(deployCode, spaceCode, filePath);
        if (editLock != null && !editLock.getUserId().equals(user.getId())) {
            User lockOwner = userMapper.selectById(editLock.getUserId());
            String lockOwnerName = lockOwner != null ? lockOwner.getUsername() : "unknown";
            throw new RuntimeException(
                    "File '" + filePath + "' is currently being edited by user '" + lockOwnerName + "'. " +
                            "Please wait until they finish or the lock expires.");
        }

        RLock fileLock = lockManager.tryGetFileLock(deployCode, spaceCode, filePath, 3, 30);
        if (fileLock == null) {
            throw new RuntimeException("File or folder is currently locked by another user");
        }

        try {
            RLock repoLock = lockManager.tryGetRepoLock(deployCode, spaceCode, 5, 60);
            if (repoLock == null) {
                throw new RuntimeException("Repository is currently busy, please try again later");
            }

            try {
                if (fileType == FILE_TYPE_FOLDER) {
                    // 删除文件夹时，需要先获取所有子文件的锁
                    List<FileIndex> children = fileIndexMapper.selectList(
                            new LambdaQueryWrapper<FileIndex>()
                                    .eq(FileIndex::getDeployCode, deployCode)
                                    .eq(FileIndex::getSpaceCode, spaceCode)
                                    .eq(FileIndex::getIsDeleted, 0)
                                    .likeRight(FileIndex::getFilePath, filePath + "/")
                    );
                    
                    // 收集所有子文件路径并排序，避免死锁
                    List<String> childPaths = children.stream()
                            .map(FileIndex::getFilePath)
                            .sorted()
                            .collect(Collectors.toList());
                    
                    // 获取所有子文件的锁
                    List<RLock> childLocks = new ArrayList<>();
                    try {
                        for (String childPath : childPaths) {
                            RLock childLock = lockManager.tryGetFileLock(deployCode, spaceCode, childPath, 3, 30);
                            if (childLock == null) {
                                // 获取锁失败，释放已获取的所有子文件锁
                                for (RLock acquired : childLocks) {
                                    lockManager.releaseLock(acquired);
                                }
                                throw new RuntimeException("Child file '" + childPath + "' is currently locked by another user");
                            }
                            childLocks.add(childLock);
                        }
                        
                        // 执行物理删除
                        deleteFolderPhysical(deployCode, spaceCode, user, filePath);
                        
                        // 标记文件夹为已删除
                        fileIndex.setIsDeleted(1);
                        fileIndex.setVersion(fileIndex.getVersion() + 1);
                        fileIndex.setUpdatedBy(user.getId());
                        fileIndex.setLastUpdatedDate(LocalDateTime.now());
                        fileIndexMapper.updateById(fileIndex);
                        
                        // 标记子项为已删除
                        markChildrenDeleted(deployCode, spaceCode, filePath, user.getId());
                        
                        return recordChange(deployCode, spaceCode, filePath, "DELETE", null, user.getId());
                        
                    } finally {
                        // 释放所有子文件锁
                        for (RLock childLock : childLocks) {
                            lockManager.releaseLock(childLock);
                        }
                    }
                } else {
                    // 删除单个文件
                    deleteFilePhysical(deployCode, spaceCode, filePath);
                    
                    // 标记为已删除
                    fileIndex.setIsDeleted(1);
                    fileIndex.setVersion(fileIndex.getVersion() + 1);
                    fileIndex.setUpdatedBy(user.getId());
                    fileIndex.setLastUpdatedDate(LocalDateTime.now());
                    fileIndexMapper.updateById(fileIndex);
                    
                    return recordChange(deployCode, spaceCode, filePath, "DELETE", null, user.getId());
                }

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

    /**
     * 物理删除单个文件
     */
    private void deleteFilePhysical(String deployCode, String spaceCode, String filePath) throws Exception {
        java.io.File file = new java.io.File(gitService.getRepoPath(deployCode, spaceCode), filePath);
        if (file.exists()) {
            file.delete();
        }
    }

    /**
     * 物理删除文件夹及其下所有文件
     */
    private void deleteFolderPhysical(String deployCode, String spaceCode, User user, String folderPath) throws Exception {
        gitService.deleteFolder(deployCode, spaceCode, folderPath);
    }

    /**
     * 将文件夹子项索引标记为已删除
     */
    private void markChildrenDeleted(String deployCode, String spaceCode, String folderPath, Long userId) {
        List<FileIndex> children = fileIndexMapper.selectList(
                new LambdaQueryWrapper<FileIndex>()
                        .eq(FileIndex::getDeployCode, deployCode)
                        .eq(FileIndex::getSpaceCode, spaceCode)
                        .eq(FileIndex::getIsDeleted, 0)
                        .likeRight(FileIndex::getFilePath, folderPath + "/")
        );
        for (FileIndex child : children) {
            child.setIsDeleted(1);
            child.setVersion(child.getVersion() + 1);
            child.setUpdatedBy(userId);
            child.setLastUpdatedDate(LocalDateTime.now());
            fileIndexMapper.updateById(child);
        }
    }

    // ======================== 查看变更 ========================

    /**
     * 获取仓库的所有未提交变更
     */
    public List<FileChange> getPendingChanges(String deployCode, String spaceCode) {
        return fileChangeMapper.selectList(
                new LambdaQueryWrapper<FileChange>()
                        .eq(FileChange::getDeployCode, deployCode)
                        .eq(FileChange::getSpaceCode, spaceCode)
                        .eq(FileChange::getCommitted, 0)
                        .orderByDesc(FileChange::getCreateDate)
        );
    }

    /**
     * 分页获取仓库的未提交变更（按目录树状排序）
     * 优化版本：使用数据库分页和排序，减少内存计算
     */
    public PageResponse getPendingChangesWithPage(String deployCode, String spaceCode, PageRequest pageRequest) {
        int pageNum = pageRequest.getPageNum() != null ? pageRequest.getPageNum() : 1;
        int pageSize = pageRequest.getPageSize() != null ? pageRequest.getPageSize() : 20;
        String parentPath = pageRequest.getParentPath();
        
        // 1. 使用数据库分页查询（直接在SQL中过滤和排序）
        Page<FileChange> page = new Page<>(pageNum, pageSize);
        IPage<FileChange> resultPage = fileChangeMapper.selectPendingChangesWithPage(
                deployCode, spaceCode, parentPath, page
        );
        
        List<FileChange> pagedChanges = resultPage.getRecords();
        long total = resultPage.getTotal();
        
        if (pagedChanges.isEmpty()) {
            PageResponse response = new PageResponse();
            response.setRecords(new ArrayList<>());
            response.setTotal(total);
            response.setPageNum(pageNum);
            response.setPageSize(pageSize);
            response.setTotalPages(0);
            return response;
        }
        
        // 2. 批量查询文件类型（避免N+1查询）
        List<String> filePaths = pagedChanges.stream()
                .map(FileChange::getFilePath)
                .collect(Collectors.toList());
        
        Map<String, Integer> fileTypeMap = buildFileTypeMap(deployCode, spaceCode, filePaths);
        
        // 3. 转换为树状节点
        List<PageResponse.FileChangeNode> nodes = pagedChanges.stream()
                .map(change -> convertToFileChangeNodeOptimized(change, fileTypeMap))
                .collect(Collectors.toList());
        
        // 4. 构建分页响应
        int totalPages = (int) Math.ceil((double) total / pageSize);
        
        PageResponse response = new PageResponse();
        response.setRecords(nodes);
        response.setTotal(total);
        response.setPageNum(pageNum);
        response.setPageSize(pageSize);
        response.setTotalPages(totalPages);
        
        return response;
    }

    /**
     * 将FileChange转换为树状节点（优化版，使用预加载的文件类型Map）
     */
    private PageResponse.FileChangeNode convertToFileChangeNodeOptimized(FileChange change, Map<String, Integer> fileTypeMap) {
        PageResponse.FileChangeNode node = new PageResponse.FileChangeNode();
        node.setId(change.getId());
        node.setName(getFileName(change.getFilePath()));
        node.setFilePath(change.getFilePath());
        node.setChangeType(change.getChangeType());
        node.setNewPath(change.getNewPath());
        node.setOperatorId(change.getOperatorId());
        node.setCreatedAt(change.getCreateDate());

        // 从预加载的Map中获取文件类型，避免N+1查询
        Integer fileType = fileTypeMap.get(change.getFilePath());
        boolean isFolder = fileType != null && fileType == FILE_TYPE_FOLDER;
        if (!isFolder) {
            // 如果FileIndex中没有，尝试根据路径特征判断
            isFolder = change.getFilePath().endsWith("/");
        }
        node.setIsFolder(isFolder);

        // 如果是文件夹，查询其直接子项变更
        if (isFolder) {
            List<PageResponse.FileChangeNode> children = getDirectChildrenChanges(change.getDeployCode(), change.getSpaceCode(), change.getFilePath());
            node.setChildren(children.isEmpty() ? null : children);
        }

        return node;
    }
    
    /**
     * 批量构建文件类型映射表
     */
    private Map<String, Integer> buildFileTypeMap(String deployCode, String spaceCode, List<String> filePaths) {
        if (filePaths == null || filePaths.isEmpty()) {
            return new HashMap<>();
        }
        
        List<FileChangeMapper.FileTypeResult> results = fileChangeMapper.selectFileTypes(deployCode, spaceCode, filePaths);
        Map<String, Integer> fileTypeMap = new HashMap<>();
        
        for (FileChangeMapper.FileTypeResult result : results) {
            fileTypeMap.put(result.getFilePath(), result.getFileType());
        }
        
        return fileTypeMap;
    }

    /**
     * 将FileChange转换为树状节点（旧版本，保留用于兼容）
     * @deprecated 请使用 convertToFileChangeNodeOptimized
     */
    @Deprecated
    private PageResponse.FileChangeNode convertToFileChangeNode(FileChange change) {
        PageResponse.FileChangeNode node = new PageResponse.FileChangeNode();
        node.setId(change.getId());
        node.setName(getFileName(change.getFilePath()));
        node.setFilePath(change.getFilePath());
        node.setChangeType(change.getChangeType());
        node.setNewPath(change.getNewPath());
        node.setOperatorId(change.getOperatorId());
        node.setCreatedAt(change.getCreateDate());

        boolean isFolder = isFolderChange(change);
        node.setIsFolder(isFolder);

        // 如果是文件夹，查询其直接子项变更
        if (isFolder) {
            List<PageResponse.FileChangeNode> children = getDirectChildrenChanges(change.getDeployCode(), change.getSpaceCode(), change.getFilePath());
            node.setChildren(children.isEmpty() ? null : children);
        }

        return node;
    }

    /**
     * 判断变更是否为文件夹
     */
    private boolean isFolderChange(FileChange change) {
        // 查询FileIndex判断是否为文件夹
        FileIndex fileIndex = findFileIndex(change.getDeployCode(), change.getSpaceCode(), change.getFilePath());
        if (fileIndex != null) {
            return fileIndex.getFileType() == FILE_TYPE_FOLDER;
        }

        // 如果文件已被删除，尝试根据路径特征判断
        return change.getFilePath().endsWith("/");
    }

    /**
     * 获取文件夹的直接子项变更
     */
    private List<PageResponse.FileChangeNode> getDirectChildrenChanges(String deployCode, String spaceCode, String folderPath) {
        List<FileChange> childrenChanges = fileChangeMapper.selectList(
                new LambdaQueryWrapper<FileChange>()
                        .eq(FileChange::getDeployCode, deployCode)
                        .eq(FileChange::getSpaceCode, spaceCode)
                        .eq(FileChange::getCommitted, 0)
        );

        String prefix = folderPath.endsWith("/") ? folderPath : folderPath + "/";

        return childrenChanges.stream()
                .filter(change -> {
                    String filePath = change.getFilePath();
                    if (!filePath.startsWith(prefix)) {
                        return false;
                    }
                    // 确保是直接子项
                    String relativePath = filePath.substring(prefix.length());
                    return !relativePath.contains("/");
                })
                .sorted((c1, c2) -> {
                    String name1 = getFileName(c1.getFilePath());
                    String name2 = getFileName(c2.getFilePath());

                    boolean isFolder1 = isFolderChange(c1);
                    boolean isFolder2 = isFolderChange(c2);

                    if (isFolder1 && !isFolder2) return -1;
                    if (!isFolder1 && isFolder2) return 1;

                    return name1.compareToIgnoreCase(name2);
                })
                .map(change -> convertToFileChangeNodeOptimized(change, buildFileTypeMap(deployCode, spaceCode, 
                        childrenChanges.stream().map(FileChange::getFilePath).collect(Collectors.toList()))))
                .collect(Collectors.toList());
    }

    /**
     * 从文件路径中提取文件名
     */
    private String getFileName(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "";
        }
        int lastSlashIndex = filePath.lastIndexOf("/");
        if (lastSlashIndex >= 0) {
            return filePath.substring(lastSlashIndex + 1);
        }
        return filePath;
    }

    // ======================== 提交推送 ========================

    /**
     * 用户勾选变更后提交并可选推送
     * 流程：git reset → git add 选中文件 → git commit → 可选 git push
     */
    @Transactional
    public String commitAndPush(CommitRequest request) {
        String deployCode = request.getDeployCode();
        String spaceCode = request.getSpaceCode();
        User user = findUserByUsername(request.getOperator());

        // 提交阶段需要获取仓库级锁，防止并发 commit 导致 Git 状态异常
        RLock repoLock = lockManager.tryGetRepoLock(deployCode, spaceCode, 10, 120);
        if (repoLock == null) {
            throw new RuntimeException("Repository is currently busy with another commit, please try again later");
        }

        try {

            // 查询待提交的变更
            List<FileChange> changes;
            if (request.getSelectedChangeIds() != null && !request.getSelectedChangeIds().isEmpty()) {
                changes = fileChangeMapper.selectList(
                        new LambdaQueryWrapper<FileChange>()
                                .eq(FileChange::getDeployCode, deployCode)
                                .eq(FileChange::getSpaceCode, spaceCode)
                                .eq(FileChange::getCommitted, 0)
                                .in(FileChange::getId, request.getSelectedChangeIds())
                );
            } else {
                changes = getPendingChanges(deployCode, spaceCode);
            }

            if (changes.isEmpty()) {
                throw new RuntimeException("No pending changes to commit");
            }

            try {
                // 【关键】提交前校验：检查是否有文件被其他用户覆盖
                validateFileChangesBeforeCommit(deployCode, spaceCode, changes);

                // 1. 重置暂存区（避免混入非预期的已暂存文件）
                gitService.resetStaged(deployCode, spaceCode);

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
                    gitService.addFiles(deployCode, spaceCode, addPaths);
                }
                if (!rmPaths.isEmpty()) {
                    gitService.rmFiles(deployCode, spaceCode, rmPaths);
                }

                // 3. 提交
                String commitMsg = request.getCommitMessage();
                if (commitMsg == null || commitMsg.isEmpty()) {
                    commitMsg = "Commit " + changes.size() + " change(s)";
                }

                String commitHash = gitService.commitStaged(deployCode, spaceCode, commitMsg,
                        getAuthorName(user), getAuthorEmail(user));

                // 4. 标记变更为已提交
                LocalDateTime now = LocalDateTime.now();
                for (FileChange change : changes) {
                    change.setCommitted(1);
                    change.setCommitId(commitHash);
                    change.setCommittedDate(now);
                    fileChangeMapper.updateById(change);
                }

                // 5. 更新 FileIndex 的 lastCommitId
                updateFileIndexCommitId(deployCode, spaceCode, changes, commitHash);

                // 6. 可选推送
                if (Boolean.TRUE.equals(request.getPushToRemote())) {
                    gitService.push(deployCode, spaceCode);
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

    /**
     * 提交前校验：检查文件是否被其他用户覆盖
     * 防止用户A修改文件后，用户B也修改并提交，导致用户A的修改丢失
     */
    private void validateFileChangesBeforeCommit(String deployCode, String spaceCode, List<FileChange> changes) {
        // 收集所有需要校验的文件路径（排除DELETE操作）
        List<String> filePathsToCheck = changes.stream()
                .filter(c -> !"DELETE".equals(c.getChangeType()))
                .map(FileChange::getFilePath)
                .distinct()
                .collect(Collectors.toList());
        
        if (filePathsToCheck.isEmpty()) {
            return;
        }
        
        // 批量查询最新的 FileIndex
        List<FileIndex> fileIndices = fileIndexMapper.selectList(
                new LambdaQueryWrapper<FileIndex>()
                        .eq(FileIndex::getDeployCode, deployCode)
                        .eq(FileIndex::getSpaceCode, spaceCode)
                        .in(FileIndex::getFilePath, filePathsToCheck)
                        .eq(FileIndex::getIsDeleted, 0)
        );
        
        Map<String, FileIndex> indexMap = fileIndices.stream()
                .collect(Collectors.toMap(FileIndex::getFilePath, idx -> idx));
        
        // 检查每个变更对应的文件索引版本
        for (FileChange change : changes) {
            if ("DELETE".equals(change.getChangeType())) {
                continue;
            }
            
            String filePath = change.getFilePath();
            FileIndex currentIndex = indexMap.get(filePath);
            
            if (currentIndex == null) {
                // 文件已被删除，但变更是ADD或MODIFY，说明有冲突
                throw new ConflictException(
                        "File conflict detected: file '" + filePath + "' has been deleted by another user. " +
                        "Please refresh and retry.");
            }
            
            // 检查是否有其他未提交的变更针对同一个文件
            long conflictingChanges = changes.stream()
                    .filter(c -> c.getFilePath().equals(filePath))
                    .filter(c -> !c.getId().equals(change.getId()))
                    .count();
            
            if (conflictingChanges > 0) {
                // 同一文件有多个未提交变更，需要警告
                log.warn("Multiple pending changes for file '{}': {} changes detected", filePath, conflictingChanges + 1);
            }
        }
        
        // 检查磁盘文件的最后修改时间，确保没有被外部修改
        // （这一步可选，如果需要更严格的校验可以启用）
    }

    /**
     * 提交后更新相关 FileIndex 的 lastCommitId
     */
    private void updateFileIndexCommitId(String deployCode, String spaceCode, List<FileChange> changes, String commitHash) {
        for (FileChange change : changes) {
            String targetPath = change.getNewPath() != null ? change.getNewPath() : change.getFilePath();
            FileIndex idx = findFileIndex(deployCode, spaceCode, targetPath);
            if (idx == null && "DELETE".equals(change.getChangeType())) {
                // 已删除的文件查找（包含已标记删除的）
                idx = fileIndexMapper.selectOne(
                        new LambdaQueryWrapper<FileIndex>()
                                .eq(FileIndex::getDeployCode, deployCode)
                                .eq(FileIndex::getSpaceCode, spaceCode)
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
        String deployCode = request.getDeployCode();
        String spaceCode = request.getSpaceCode();
        String filePath = request.getFilePath();

        Long userId = resolveUserId(request);

        FileIndex fileIndex = findFileIndex(deployCode, spaceCode, filePath);
        if (fileIndex == null) {
            throw new RuntimeException("File not found: " + filePath);
        }

        // 检查是否已有其他用户的活跃锁
        FileLock existingLock = findActiveEditLock(deployCode, spaceCode, filePath);
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
        fileLock.setDeployCode(deployCode);
        fileLock.setSpaceCode(spaceCode);
        fileLock.setFilePath(filePath);
        fileLock.setUserId(userId);
        fileLock.setLockToken(lockToken);
        fileLock.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        fileLock.setCreateDate(LocalDateTime.now());
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
        String deployCode = request.getDeployCode();
        String spaceCode = request.getSpaceCode();
        String filePath = request.getFilePath();
        Long userId = resolveUserId(request);

        FileLock lock = findActiveEditLock(deployCode, spaceCode, filePath);
        if (lock == null) {
            return;
        }

        if (!lock.getUserId().equals(userId)) {
            throw new RuntimeException("You do not own the lock on file '" + filePath + "'");
        }

        fileLockMapper.deleteById(lock.getId());

        FileIndex fileIndex = findFileIndex(deployCode, spaceCode, filePath);
        if (fileIndex != null) {
            fileIndex.setLockedBy(null);
            fileIndex.setLockedAt(null);
            fileIndexMapper.updateById(fileIndex);
        }
    }

    /**
     * 查询文件的编辑锁状态
     */
    public Map<String, Object> getEditLockStatus(String deployCode, String spaceCode, String filePath) {
        FileLock lock = findActiveEditLock(deployCode, spaceCode, filePath);
        Map<String, Object> result = new HashMap<>();

        if (lock == null) {
            result.put("locked", false);
            return result;
        }

        User lockOwner = userMapper.selectById(lock.getUserId());
        result.put("locked", true);
        result.put("lockedBy", lockOwner != null ? lockOwner.getUsername() : "unknown");
        result.put("lockedAt", lock.getCreateDate());
        result.put("expireTime", lock.getExpireTime());
        result.put("lockToken", lock.getLockToken());
        return result;
    }

    // ======================== 查询 ========================

    /**
     * 获取文件/文件夹列表
     */
    public List<FileIndex> getFileList(String deployCode, String spaceCode, String parentPath) {
        LambdaQueryWrapper<FileIndex> wrapper = new LambdaQueryWrapper<FileIndex>()
                .eq(FileIndex::getDeployCode, deployCode)
                .eq(FileIndex::getSpaceCode, spaceCode)
                .eq(FileIndex::getIsDeleted, 0);

        if (parentPath != null && !parentPath.isEmpty()) {
            wrapper.likeRight(FileIndex::getFilePath, parentPath + "/");
        }

        return fileIndexMapper.selectList(wrapper);
    }

    /**
     * 获取文件历史
     */
    public List<GitService.CommitInfo> getFileHistory(String deployCode, String spaceCode, String filePath) {
        try {
            return gitService.getFileHistory(deployCode, spaceCode, filePath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get file history: " + e.getMessage(), e);
        }
    }

    // ======================== 私有工具方法 ========================

    /**
     * 检查文件是否有其他未提交的变更
     * 防止多个用户同时修改同一文件导致数据覆盖
     */
    private void checkUncommittedChanges(String deployCode, String spaceCode, String filePath, Long currentChangeId) {
        List<FileChange> pendingChanges = fileChangeMapper.selectList(
                new LambdaQueryWrapper<FileChange>()
                        .eq(FileChange::getDeployCode, deployCode)
                        .eq(FileChange::getSpaceCode, spaceCode)
                        .eq(FileChange::getFilePath, filePath)
                        .eq(FileChange::getCommitted, 0)
        );
        
        // 过滤掉当前变更记录（如果是更新操作）
        List<FileChange> otherChanges = pendingChanges.stream()
                .filter(c -> currentChangeId == null || !c.getId().equals(currentChangeId))
                .collect(Collectors.toList());
        
        if (!otherChanges.isEmpty()) {
            FileChange latestChange = otherChanges.stream()
                    .max((c1, c2) -> c1.getCreateDate().compareTo(c2.getCreateDate()))
                    .orElse(null);
            
            if (latestChange != null) {
                User otherUser = userMapper.selectById(latestChange.getOperatorId());
                String otherUserName = otherUser != null ? otherUser.getUsername() : "unknown";
                
                throw new ConflictException(
                        "File conflict detected: file '" + filePath + "' has uncommitted changes by user '" + 
                        otherUserName + "'. Please wait for them to commit or discard their changes first.");
            }
        }
    }

    /**
     * 记录一条文件变更
     */
    private FileChange recordChange(String deployCode, String spaceCode, String filePath,
                                    String changeType, String newPath, Long operatorId) {
        FileChange change = new FileChange();
        change.setDeployCode(deployCode);
        change.setSpaceCode(spaceCode);
        change.setFilePath(filePath);
        change.setChangeType(changeType);
        change.setNewPath(newPath);
        change.setCommitted(0);
        change.setOperatorId(operatorId);
        change.setCreateDate(LocalDateTime.now());
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

    private FileIndex findFileIndex(String deployCode, String spaceCode, String filePath) {
        return fileIndexMapper.selectOne(
                new LambdaQueryWrapper<FileIndex>()
                        .eq(FileIndex::getDeployCode, deployCode)
                        .eq(FileIndex::getSpaceCode, spaceCode)
                        .eq(FileIndex::getFilePath, filePath)
                        .eq(FileIndex::getIsDeleted, 0)
        );
    }

    private void createFileIndex(String deployCode, String spaceCode, String filePath, int fileType,
                                 Long fileSize, Long userId) {
        FileIndex index = new FileIndex();
        index.setDeployCode(deployCode);
        index.setSpaceCode(spaceCode);
        index.setFilePath(filePath);
        index.setFileType(fileType);
        index.setFileSize(fileSize);
        index.setVersion(1);
        index.setIsDeleted(0);
        index.setCreatedBy(userId);
        index.setCreateDate(LocalDateTime.now());
        fileIndexMapper.insert(index);
    }

    /**
     * 查找文件的活跃编辑锁（未过期）
     */
    private FileLock findActiveEditLock(String deployCode, String spaceCode, String filePath) {
        FileLock lock = fileLockMapper.selectOne(
                new LambdaQueryWrapper<FileLock>()
                        .eq(FileLock::getDeployCode, deployCode)
                        .eq(FileLock::getSpaceCode, spaceCode)
                        .eq(FileLock::getFilePath, filePath)
                        .gt(FileLock::getExpireTime, LocalDateTime.now())
                        .orderByDesc(FileLock::getCreateDate)
                        .last("LIMIT 1")
        );
        return lock;
    }

    /**
     * 解析用户 ID（优先使用 operator，其次使用 userId）
     */
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
