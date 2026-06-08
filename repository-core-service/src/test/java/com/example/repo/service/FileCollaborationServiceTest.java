package com.example.repo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * FileCollaborationService 单元测试
 * 覆盖核心接口全场景：创建、更新、删除、编辑锁管理、乐观锁冲突、提交保护
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("文件协作服务测试")
class FileCollaborationServiceTest {

    @InjectMocks
    private FileCollaborationService fileCollaborationService;

    @Mock
    private DistributedLockManager lockManager;

    @Mock
    private GitService gitService;

    @Mock
    private FileIndexMapper fileIndexMapper;

    @Mock
    private FileLockMapper fileLockMapper;

    @Mock
    private FileChangeMapper fileChangeMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private RLock mockFileLock;

    @Mock
    private RLock mockRepoLock;

    private static final String DEPLOY_CODE = "deploy_code";
    private static final String SPACE_CODE = "space_code";
    private static final String OPERATOR = "testuser";
    private static final String FILE_PATH = "docs/readme.txt";
    private static final String FOLDER_PATH = "docs/subfolder";

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(100L);
        testUser.setUsername("testuser");
        testUser.setEmail("testuser@example.com");
        testUser.setFullName("Test User");
    }

    // ======================== 辅助方法 ========================

    private void mockUserExists() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(testUser);
    }

    private void mockUserNotFound() {
        when(userMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    }

    private void mockLocksAcquired() {
        when(lockManager.tryGetFileLock(anyString(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(mockFileLock);
        when(lockManager.tryGetRepoLock(anyString(), anyString(), anyLong(), anyLong())).thenReturn(mockRepoLock);
    }

    private void mockFileLockFailed() {
        when(lockManager.tryGetFileLock(anyString(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(null);
    }

    private void mockRepoLockFailed() {
        when(lockManager.tryGetFileLock(anyString(), anyString(), anyString(), anyLong(), anyLong())).thenReturn(mockFileLock);
        when(lockManager.tryGetRepoLock(anyString(), anyString(), anyLong(), anyLong())).thenReturn(null);
    }

    private void mockNoExistingFileIndex() {
        when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    }

    /**
     * 模拟无活跃编辑锁
     */
    private void mockNoActiveEditLock() {
        when(fileLockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
    }

    private FileIndex buildFileIndex(String path, int fileType) {
        FileIndex index = new FileIndex();
        index.setId(1L);
        index.setDeployCode(DEPLOY_CODE);
        index.setSpaceCode(SPACE_CODE);
        index.setFilePath(path);
        index.setFileType(fileType);
        index.setFileSize(0L);
        index.setLastCommitId("old_hash");
        index.setVersion(1);
        index.setIsDeleted(0);
        index.setCreatedBy(testUser.getId());
        return index;
    }

    private FileOperationRequest buildCreateFileRequest(String path, String content) {
        FileOperationRequest req = new FileOperationRequest();
        req.setDeployCode(DEPLOY_CODE);
        req.setSpaceCode(SPACE_CODE);
        req.setFilePath(path);
        req.setContent(content);
        req.setOperator(OPERATOR);
        req.setFileType(1);
        return req;
    }

    private FileOperationRequest buildCreateFolderRequest(String path) {
        FileOperationRequest req = new FileOperationRequest();
        req.setDeployCode(DEPLOY_CODE);
        req.setSpaceCode(SPACE_CODE);
        req.setFilePath(path);
        req.setOperator(OPERATOR);
        req.setFileType(2);
        return req;
    }

    private FileLock buildFileLock(Long userId, String filePath) {
        FileLock lock = new FileLock();
        lock.setId(1L);
        lock.setDeployCode(DEPLOY_CODE);
        lock.setSpaceCode(SPACE_CODE);
        lock.setFilePath(filePath);
        lock.setUserId(userId);
        lock.setLockToken("test-token-123");
        lock.setExpireTime(LocalDateTime.now().plusMinutes(5));
        lock.setCreateDate(LocalDateTime.now());
        return lock;
    }

    // ======================== 创建接口测试 ========================

    @Nested
    @DisplayName("创建文件或文件夹 - createFileOrFolder")
    class CreateTests {

        @Test
        @DisplayName("创建文件 - 正常场景，含内容，不执行commit")
        void createFile_withContent_success() throws Exception {
            mockUserExists();
            mockNoExistingFileIndex();
            mockLocksAcquired();

            FileOperationRequest request = buildCreateFileRequest(FILE_PATH, "Hello World");
            FileChange result = fileCollaborationService.createFileOrFolder(request);

            assertNotNull(result);
            assertEquals(FILE_PATH, result.getFilePath());
            assertEquals("ADD", result.getChangeType());
            assertEquals(0, result.getCommitted());

            verify(gitService).writeFileContent(DEPLOY_CODE, SPACE_CODE, FILE_PATH, "Hello World");
            verify(gitService, never()).commit(anyString(), anyString(), anyString(), anyString(), anyString());

            // 验证索引创建
            ArgumentCaptor<FileIndex> captor = ArgumentCaptor.forClass(FileIndex.class);
            verify(fileIndexMapper).insert(captor.capture());
            FileIndex saved = captor.getValue();
            assertEquals(1, saved.getFileType());
            assertEquals(FILE_PATH, saved.getFilePath());
            assertEquals(11L, saved.getFileSize());

            // 验证变更记录
            verify(fileChangeMapper).insert(any(FileChange.class));

            verify(lockManager).releaseLock(mockRepoLock);
            verify(lockManager).releaseLock(mockFileLock);
        }

        @Test
        @DisplayName("创建文件 - 内容为null时使用空字符串")
        void createFile_withNullContent_success() throws Exception {
            mockUserExists();
            mockNoExistingFileIndex();
            mockLocksAcquired();

            FileOperationRequest request = buildCreateFileRequest(FILE_PATH, null);
            FileChange result = fileCollaborationService.createFileOrFolder(request);

            assertNotNull(result);
            verify(gitService).writeFileContent(DEPLOY_CODE, SPACE_CODE, FILE_PATH, "");

            ArgumentCaptor<FileIndex> captor = ArgumentCaptor.forClass(FileIndex.class);
            verify(fileIndexMapper).insert(captor.capture());
            assertEquals(0L, captor.getValue().getFileSize());
        }

        @Test
        @DisplayName("创建文件夹 - 正常场景")
        void createFolder_success() throws Exception {
            mockUserExists();
            mockNoExistingFileIndex();
            mockLocksAcquired();

            FileOperationRequest request = buildCreateFolderRequest(FOLDER_PATH);
            FileChange result = fileCollaborationService.createFileOrFolder(request);

            assertNotNull(result);
            assertEquals("ADD", result.getChangeType());
            verify(gitService).createFolder(DEPLOY_CODE, SPACE_CODE, FOLDER_PATH);
            verify(gitService, never()).commit(anyString(), anyString(), anyString(), anyString(), anyString());

            ArgumentCaptor<FileIndex> captor = ArgumentCaptor.forClass(FileIndex.class);
            verify(fileIndexMapper).insert(captor.capture());
            assertEquals(2, captor.getValue().getFileType());
            assertEquals(0L, captor.getValue().getFileSize());
        }

        @Test
        @DisplayName("创建文件 - fileType为null时默认创建文件")
        void createFile_defaultFileType_success() throws Exception {
            mockUserExists();
            mockNoExistingFileIndex();
            mockLocksAcquired();

            FileOperationRequest request = buildCreateFileRequest(FILE_PATH, "data");
            request.setFileType(null);
            fileCollaborationService.createFileOrFolder(request);

            verify(gitService).writeFileContent(DEPLOY_CODE, SPACE_CODE, FILE_PATH, "data");
            verify(gitService, never()).createFolder(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("创建失败 - NAS不可用")
        void create_nasUnavailable_throwsException() {
            FileOperationRequest request = buildCreateFileRequest(FILE_PATH, "content");
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.createFileOrFolder(request));
            assertTrue(ex.getMessage().contains("NAS storage is currently unavailable"));
        }

        @Test
        @DisplayName("创建失败 - 用户不存在")
        void create_userNotFound_throwsException() {
            mockUserNotFound();
            FileOperationRequest request = buildCreateFileRequest(FILE_PATH, "content");
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.createFileOrFolder(request));
            assertTrue(ex.getMessage().contains("User not found"));
        }

        @Test
        @DisplayName("创建文件失败 - 文件已存在")
        void createFile_alreadyExists_throwsException() {
            mockUserExists();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(buildFileIndex(FILE_PATH, 1));
            FileOperationRequest request = buildCreateFileRequest(FILE_PATH, "content");
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.createFileOrFolder(request));
            assertTrue(ex.getMessage().contains("File already exists"));
        }

        @Test
        @DisplayName("创建失败 - 文件锁获取失败（并发冲突）")
        void create_fileLockFailed_throwsException() {
            mockUserExists();
            mockNoExistingFileIndex();
            mockFileLockFailed();
            FileOperationRequest request = buildCreateFileRequest(FILE_PATH, "content");
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.createFileOrFolder(request));
            assertTrue(ex.getMessage().contains("Operation is currently in progress"));
        }

        @Test
        @DisplayName("创建失败 - 仓库锁获取失败（仓库繁忙）")
        void create_repoLockFailed_throwsException() {
            mockUserExists();
            mockNoExistingFileIndex();
            mockRepoLockFailed();
            FileOperationRequest request = buildCreateFileRequest(FILE_PATH, "content");
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.createFileOrFolder(request));
            assertTrue(ex.getMessage().contains("Repository is currently busy"));
            verify(lockManager).releaseLock(mockFileLock);
        }

        @Test
        @DisplayName("创建文件 - Git操作异常")
        void createFile_gitException_throwsException() throws Exception {
            mockUserExists();
            mockNoExistingFileIndex();
            mockLocksAcquired();
            doThrow(new java.io.IOException("Disk full")).when(gitService).writeFileContent(anyString(), anyString(), anyString(), anyString());

            FileOperationRequest request = buildCreateFileRequest(FILE_PATH, "content");
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.createFileOrFolder(request));
            assertTrue(ex.getMessage().contains("Failed to create"));
            verify(lockManager).releaseLock(mockRepoLock);
            verify(lockManager).releaseLock(mockFileLock);
        }
    }

    // ======================== 更新接口测试 ========================

    @Nested
    @DisplayName("更新文件或文件夹 - updateFileOrFolder")
    class UpdateTests {

        @Test
        @DisplayName("更新文件内容 - 正常场景，不执行commit")
        void updateFile_contentOnly_success() throws Exception {
            FileIndex existingFile = buildFileIndex(FILE_PATH, 1);
            mockUserExists();
            mockNoActiveEditLock();
            mockLocksAcquired();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingFile);

            FileOperationRequest request = new FileOperationRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setContent("Updated content");
            request.setOperator(OPERATOR);

            FileChange result = fileCollaborationService.updateFileOrFolder(request);

            assertNotNull(result);
            assertEquals("MODIFY", result.getChangeType());
            assertEquals(0, result.getCommitted());

            verify(gitService).writeFileContent(DEPLOY_CODE, SPACE_CODE, FILE_PATH, "Updated content");
            verify(gitService, never()).commit(anyString(), anyString(), anyString(), anyString(), anyString());

            // 验证索引版本号递增
            verify(fileIndexMapper).updateById(argThat(idx -> idx.getVersion() == 2));
            verify(fileChangeMapper).insert(any(FileChange.class));
        }

        @Test
        @DisplayName("更新文件 - 带乐观锁版本号匹配")
        void updateFile_withExpectedVersion_success() throws Exception {
            FileIndex existingFile = buildFileIndex(FILE_PATH, 1);
            existingFile.setVersion(5);
            mockUserExists();
            mockNoActiveEditLock();
            mockLocksAcquired();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingFile);

            FileOperationRequest request = new FileOperationRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setContent("Updated");
            request.setOperator(OPERATOR);
            request.setExpectedVersion(5); // 版本匹配

            FileChange result = fileCollaborationService.updateFileOrFolder(request);
            assertNotNull(result);
            verify(gitService).writeFileContent(DEPLOY_CODE, SPACE_CODE, FILE_PATH, "Updated");
        }

        @Test
        @DisplayName("更新文件 - 乐观锁版本冲突，抛出ConflictException")
        void updateFile_versionConflict_throwsConflictException() {
            FileIndex existingFile = buildFileIndex(FILE_PATH, 1);
            existingFile.setVersion(3); // 当前版本3
            mockUserExists();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingFile);

            FileOperationRequest request = new FileOperationRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setContent("Updated");
            request.setOperator(OPERATOR);
            request.setExpectedVersion(1); // 客户端持有版本1，与当前版本3不一致

            ConflictException ex = assertThrows(ConflictException.class,
                    () -> fileCollaborationService.updateFileOrFolder(request));
            assertTrue(ex.getMessage().contains("File conflict detected"));
            assertTrue(ex.getMessage().contains("expected version 1"));
            assertTrue(ex.getMessage().contains("current version 3"));

            // 不应获取文件锁（在版本校验前就拒绝了）
            verify(lockManager, never()).tryGetFileLock(anyString(), anyString(), anyString(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("更新文件 - 未传expectedVersion时跳过版本校验")
        void updateFile_noExpectedVersion_skipsVersionCheck() throws Exception {
            FileIndex existingFile = buildFileIndex(FILE_PATH, 1);
            existingFile.setVersion(10);
            
            mockUserExists();
            mockNoActiveEditLock();
            mockLocksAcquired();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingFile);

            FileOperationRequest request = new FileOperationRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setContent("Updated");
            request.setOperator(OPERATOR);
            // expectedVersion 不设置，应跳过校验

            FileChange result = fileCollaborationService.updateFileOrFolder(request);
            assertNotNull(result);
            verify(gitService).writeFileContent(DEPLOY_CODE, SPACE_CODE, FILE_PATH, "Updated");
        }

        @Test
        @DisplayName("更新文件 - 被其他用户编辑锁锁定，拒绝更新")
        void updateFile_editLockedByOtherUser_throwsException() {
            FileIndex existingFile = buildFileIndex(FILE_PATH, 1);
            
            mockUserExists();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingFile);

            // 模拟其他用户(user 200L)持有编辑锁
            FileLock otherUserLock = buildFileLock(200L, FILE_PATH);
            when(fileLockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(otherUserLock);
            when(userMapper.selectById(200L)).thenReturn(testUser);

            FileOperationRequest request = new FileOperationRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setContent("Updated");
            request.setOperator(OPERATOR);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.updateFileOrFolder(request));
            assertTrue(ex.getMessage().contains("currently being edited by user"));
        }

        @Test
        @DisplayName("重命名文件 - 仅newPath")
        void renameFile_onlyNewPath_success() throws Exception {
            FileIndex existingFile = buildFileIndex(FILE_PATH, 1);
            String newPath = "docs/renamed.txt";
            
            mockUserExists();
            mockNoActiveEditLock();
            mockLocksAcquired();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingFile);

            FileOperationRequest request = new FileOperationRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setNewPath(newPath);
            request.setOperator(OPERATOR);

            FileChange result = fileCollaborationService.updateFileOrFolder(request);
            assertNotNull(result);
            verify(gitService).renameFile(DEPLOY_CODE, SPACE_CODE, FILE_PATH, newPath);
            verify(fileIndexMapper).updateById(argThat(idx ->
                    idx.getFilePath().equals(newPath) && idx.getVersion() == 2));
        }

        @Test
        @DisplayName("重命名文件并同时更新内容")
        void renameFile_andUpdateContent_success() throws Exception {
            FileIndex existingFile = buildFileIndex(FILE_PATH, 1);
            String newPath = "docs/renamed.txt";
            
            mockUserExists();
            mockNoActiveEditLock();
            mockLocksAcquired();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingFile);

            FileOperationRequest request = new FileOperationRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setNewPath(newPath);
            request.setContent("brand new content");
            request.setOperator(OPERATOR);

            FileChange result = fileCollaborationService.updateFileOrFolder(request);
            assertNotNull(result);
            verify(gitService).renameFile(DEPLOY_CODE, SPACE_CODE, FILE_PATH, newPath);
            verify(gitService).writeFileContent(DEPLOY_CODE, SPACE_CODE, newPath, "brand new content");
        }

        @Test
        @DisplayName("更新失败 - NAS不可用")
        void update_nasUnavailable_throwsException() {
            FileOperationRequest request = new FileOperationRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setContent("data");
            request.setOperator(OPERATOR);
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.updateFileOrFolder(request));
            assertTrue(ex.getMessage().contains("NAS storage is currently unavailable"));
        }

        @Test
        @DisplayName("更新失败 - 文件不存在")
        void update_notFound_throwsException() {
            
            mockUserExists();
            mockNoExistingFileIndex();
            FileOperationRequest request = new FileOperationRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath("nonexistent.txt");
            request.setContent("data");
            request.setOperator(OPERATOR);
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.updateFileOrFolder(request));
            assertTrue(ex.getMessage().contains("File or folder not found"));
        }

        @Test
        @DisplayName("更新失败 - 文件锁获取失败")
        void update_fileLockFailed_throwsException() {
            FileIndex existingFile = buildFileIndex(FILE_PATH, 1);
            
            mockUserExists();
            mockNoActiveEditLock();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingFile);
            mockFileLockFailed();
            FileOperationRequest request = new FileOperationRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setContent("data");
            request.setOperator(OPERATOR);
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.updateFileOrFolder(request));
            assertTrue(ex.getMessage().contains("currently locked"));
        }

        @Test
        @DisplayName("更新失败 - 仓库锁获取失败")
        void update_repoLockFailed_throwsException() {
            FileIndex existingFile = buildFileIndex(FILE_PATH, 1);
            
            mockUserExists();
            mockNoActiveEditLock();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingFile);
            mockRepoLockFailed();
            FileOperationRequest request = new FileOperationRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setContent("data");
            request.setOperator(OPERATOR);
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.updateFileOrFolder(request));
            assertTrue(ex.getMessage().contains("Repository is currently busy"));
        }

        @Test
        @DisplayName("更新文件 - content和newPath都为null时抛异常")
        void updateFile_noContentAndNoNewPath_throwsException() throws Exception {
            FileIndex existingFile = buildFileIndex(FILE_PATH, 1);
            
            mockUserExists();
            mockNoActiveEditLock();
            mockLocksAcquired();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingFile);

            FileOperationRequest request = new FileOperationRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setOperator(OPERATOR);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.updateFileOrFolder(request));
            assertTrue(ex.getMessage().contains("Either content or newPath must be provided"));
        }

        @Test
        @DisplayName("更新文件 - Git操作异常")
        void updateFile_gitException_throwsException() throws Exception {
            FileIndex existingFile = buildFileIndex(FILE_PATH, 1);
            
            mockUserExists();
            mockNoActiveEditLock();
            mockLocksAcquired();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingFile);
            doThrow(new java.io.IOException("Permission denied"))
                    .when(gitService).writeFileContent(anyString(), anyString(), anyString(), anyString());

            FileOperationRequest request = new FileOperationRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setContent("new data");
            request.setOperator(OPERATOR);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.updateFileOrFolder(request));
            assertTrue(ex.getMessage().contains("Failed to update"));
            verify(lockManager).releaseLock(mockRepoLock);
            verify(lockManager).releaseLock(mockFileLock);
        }
    }

    // ======================== 删除接口测试 ========================

    @Nested
    @DisplayName("删除文件或文件夹 - deleteFileOrFolder")
    class DeleteTests {
        @Test
        @DisplayName("删除文件 - 正常场景，不执行commit")
        void deleteFile_success() throws Exception {
            FileIndex existingFile = buildFileIndex(FILE_PATH, 1);
            
            mockUserExists();
            mockNoActiveEditLock();
            mockLocksAcquired();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingFile);

            FileChange result = fileCollaborationService.deleteFileOrFolder(DEPLOY_CODE, SPACE_CODE, FILE_PATH, OPERATOR);

            assertNotNull(result);
            assertEquals("DELETE", result.getChangeType());
            assertEquals(0, result.getCommitted());
            verify(gitService, never()).commit(anyString(), anyString(), anyString(), anyString(), anyString());

            // 验证索引标记为已删除
            verify(fileIndexMapper).updateById(argThat(idx ->
                    idx.getIsDeleted() == 1 && idx.getVersion() == 2));
            verify(fileChangeMapper).insert(any(FileChange.class));
            verify(lockManager).releaseLock(mockRepoLock);
            verify(lockManager).releaseLock(mockFileLock);
        }

        @Test
        @DisplayName("删除文件夹 - 正常场景，无子项")
        void deleteFolder_empty_success() throws Exception {
            FileIndex folderIndex = buildFileIndex(FOLDER_PATH, 2);
            
            mockUserExists();
            mockNoActiveEditLock();
            mockLocksAcquired();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(folderIndex);
            when(fileIndexMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

            FileChange result = fileCollaborationService.deleteFileOrFolder(DEPLOY_CODE, SPACE_CODE, FOLDER_PATH, OPERATOR);

            assertNotNull(result);
            assertEquals("DELETE", result.getChangeType());
            verify(gitService).deleteFolder(DEPLOY_CODE, SPACE_CODE, FOLDER_PATH);
            verify(fileIndexMapper).updateById(argThat(idx ->
                    idx.getIsDeleted() == 1 && idx.getFilePath().equals(FOLDER_PATH)));
        }

        @Test
        @DisplayName("删除文件夹 - 级联标记子文件索引删除")
        void deleteFolder_withChildren_cascadeDelete() throws Exception {
            FileIndex folderIndex = buildFileIndex(FOLDER_PATH, 2);
            FileIndex child1 = buildFileIndex(FOLDER_PATH + "/file1.txt", 1);
            FileIndex child2 = buildFileIndex(FOLDER_PATH + "/file2.txt", 1);

            
            mockUserExists();
            mockNoActiveEditLock();
            mockLocksAcquired();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(folderIndex);
            when(fileIndexMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(child1, child2));

            FileChange result = fileCollaborationService.deleteFileOrFolder(DEPLOY_CODE, SPACE_CODE, FOLDER_PATH, OPERATOR);

            assertNotNull(result);
            // 文件夹本身 + 2个子项 = 3次
            verify(fileIndexMapper, times(3)).updateById(argThat(idx -> idx.getIsDeleted() == 1));
        }

        @Test
        @DisplayName("删除失败 - 被其他用户编辑锁锁定")
        void delete_editLockedByOtherUser_throwsException() {
            FileIndex existingFile = buildFileIndex(FILE_PATH, 1);
            
            mockUserExists();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingFile);

            FileLock otherUserLock = buildFileLock(200L, FILE_PATH);
            when(fileLockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(otherUserLock);
            when(userMapper.selectById(200L)).thenReturn(testUser);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.deleteFileOrFolder(DEPLOY_CODE, SPACE_CODE, FILE_PATH, OPERATOR));
            assertTrue(ex.getMessage().contains("currently being edited by user"));
        }

        @Test
        @DisplayName("删除失败 - NAS不可用")
        void delete_nasUnavailable_throwsException() {
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.deleteFileOrFolder(DEPLOY_CODE, SPACE_CODE, FILE_PATH, OPERATOR));
            assertTrue(ex.getMessage().contains("NAS storage is currently unavailable"));
        }

        @Test
        @DisplayName("删除失败 - 用户不存在")
        void delete_userNotFound_throwsException() {
            
            mockUserNotFound();
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.deleteFileOrFolder(DEPLOY_CODE, SPACE_CODE, FILE_PATH, OPERATOR));
            assertTrue(ex.getMessage().contains("User not found"));
        }

        @Test
        @DisplayName("删除失败 - 文件锁获取失败")
        void delete_fileLockFailed_throwsException() {
            FileIndex existingFile = buildFileIndex(FILE_PATH, 1);
            
            mockUserExists();
            mockNoActiveEditLock();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingFile);
            mockFileLockFailed();
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.deleteFileOrFolder(DEPLOY_CODE, SPACE_CODE, FILE_PATH, OPERATOR));
            assertTrue(ex.getMessage().contains("currently locked"));
        }
    }

    // ======================== 编辑锁管理测试 ========================

    @Nested
    @DisplayName("编辑锁管理 - acquireEditLock / releaseEditLock / getEditLockStatus")
    class EditLockTests {

        @Test
        @DisplayName("获取编辑锁 - 正常场景")
        void acquireEditLock_success() {
            mockUserExists();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(buildFileIndex(FILE_PATH, 1));
            mockNoActiveEditLock(); // 无已有锁
            when(fileLockMapper.insert(any(FileLock.class))).thenReturn(1);

            FileLockRequest request = new FileLockRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setOperator(OPERATOR);

            FileLock result = fileCollaborationService.acquireEditLock(request);

            assertNotNull(result);
            assertEquals(SPACE_CODE, result.getSpaceCode());
            assertEquals(DEPLOY_CODE, result.getDeployCode());
            assertEquals(FILE_PATH, result.getFilePath());
            assertEquals(testUser.getId(), result.getUserId());
            assertNotNull(result.getLockToken());
            verify(fileLockMapper).insert(any(FileLock.class));
            verify(fileIndexMapper).updateById(argThat(idx -> idx.getLockedBy().equals(testUser.getId())));
        }

        @Test
        @DisplayName("获取编辑锁 - 同一用户续期")
        void acquireEditLock_sameUser_renew() {
            mockUserExists();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(buildFileIndex(FILE_PATH, 1));

            FileLock existingLock = buildFileLock(testUser.getId(), FILE_PATH);
            when(fileLockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingLock);

            FileLockRequest request = new FileLockRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setOperator(OPERATOR);
            request.setExpireSeconds(600);

            FileLock result = fileCollaborationService.acquireEditLock(request);

            assertNotNull(result);
            verify(fileLockMapper).updateById(any(FileLock.class));
            verify(fileLockMapper, never()).insert(any(FileLock.class));
        }

        @Test
        @DisplayName("获取编辑锁失败 - 其他用户已锁定")
        void acquireEditLock_lockedByOther_throwsException() {
            mockUserExists();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(buildFileIndex(FILE_PATH, 1));

            FileLock otherLock = buildFileLock(200L, FILE_PATH);
            when(fileLockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(otherLock);
            when(userMapper.selectById(200L)).thenReturn(testUser);

            FileLockRequest request = new FileLockRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setOperator(OPERATOR);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.acquireEditLock(request));
            assertTrue(ex.getMessage().contains("already locked by user"));
        }

        @Test
        @DisplayName("释放编辑锁 - 正常场景")
        void releaseEditLock_success() {
            mockUserExists();
            FileLock myLock = buildFileLock(testUser.getId(), FILE_PATH);
            when(fileLockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(myLock);
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(buildFileIndex(FILE_PATH, 1));

            FileLockRequest request = new FileLockRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setOperator(OPERATOR);

            fileCollaborationService.releaseEditLock(request);

            verify(fileLockMapper).deleteById(myLock.getId());
            verify(fileIndexMapper).updateById(argThat(idx ->
                    idx.getLockedBy() == null && idx.getLockedAt() == null));
        }

        @Test
        @DisplayName("释放编辑锁 - 无活跃锁时不报错")
        void releaseEditLock_noActiveLock_noOp() {
            mockUserExists();
            mockNoActiveEditLock();

            FileLockRequest request = new FileLockRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setOperator(OPERATOR);

            assertDoesNotThrow(() -> fileCollaborationService.releaseEditLock(request));
            verify(fileLockMapper, never()).deleteById(any(Long.class));
        }

        @Test
        @DisplayName("释放编辑锁失败 - 不是锁持有者")
        void releaseEditLock_notOwner_throwsException() {
            mockUserExists();
            FileLock otherLock = buildFileLock(200L, FILE_PATH);
            when(fileLockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(otherLock);

            FileLockRequest request = new FileLockRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setOperator(OPERATOR);

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> fileCollaborationService.releaseEditLock(request));
            assertTrue(ex.getMessage().contains("do not own the lock"));
        }

        @Test
        @DisplayName("查询编辑锁状态 - 未被锁定")
        void getEditLockStatus_notLocked() {
            mockNoActiveEditLock();

            Map<String, Object> status = fileCollaborationService.getEditLockStatus(DEPLOY_CODE, SPACE_CODE, FILE_PATH);
            assertFalse((Boolean) status.get("locked"));
        }

        @Test
        @DisplayName("查询编辑锁状态 - 已被锁定")
        void getEditLockStatus_locked() {
            FileLock lock = buildFileLock(testUser.getId(), FILE_PATH);
            when(fileLockMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(lock);
            when(userMapper.selectById(testUser.getId())).thenReturn(testUser);

            Map<String, Object> status = fileCollaborationService.getEditLockStatus(DEPLOY_CODE, SPACE_CODE, FILE_PATH);
            assertTrue((Boolean) status.get("locked"));
            assertEquals("testuser", status.get("lockedBy"));
            assertNotNull(status.get("expireTime"));
            assertNotNull(status.get("lockToken"));
        }
    }

    // ======================== 乐观锁冲突专项测试 ========================

    @Nested
    @DisplayName("乐观锁冲突检测")
    class OptimisticLockTests {

        @Test
        @DisplayName("版本一致 - 更新成功")
        void versionMatch_updateSuccess() throws Exception {
            FileIndex existingFile = buildFileIndex(FILE_PATH, 1);
            existingFile.setVersion(7);
            
            mockUserExists();
            mockNoActiveEditLock();
            mockLocksAcquired();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingFile);

            FileOperationRequest request = new FileOperationRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setContent("content");
            request.setOperator(OPERATOR);
            request.setExpectedVersion(7);

            FileChange result = fileCollaborationService.updateFileOrFolder(request);
            assertNotNull(result);
        }

        @Test
        @DisplayName("版本不一致 - 抛出ConflictException含详细信息")
        void versionMismatch_throwsConflictException() {
            FileIndex existingFile = buildFileIndex(FILE_PATH, 1);
            existingFile.setVersion(10);
            
            mockUserExists();
            when(fileIndexMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existingFile);

            FileOperationRequest request = new FileOperationRequest();
            request.setDeployCode(DEPLOY_CODE);
            request.setSpaceCode(SPACE_CODE);
            request.setFilePath(FILE_PATH);
            request.setContent("content");
            request.setOperator(OPERATOR);
            request.setExpectedVersion(5);

            ConflictException ex = assertThrows(ConflictException.class,
                    () -> fileCollaborationService.updateFileOrFolder(request));
            assertTrue(ex.getMessage().contains("docs/readme.txt"));
            assertTrue(ex.getMessage().contains("expected version 5"));
            assertTrue(ex.getMessage().contains("current version 10"));
            assertTrue(ex.getMessage().contains("Please reload"));
        }
    }
}
