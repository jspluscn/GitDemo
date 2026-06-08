package com.example.repo.controller;

import com.example.repo.dto.ApiResponse;
import com.example.repo.dto.CommitRequest;
import com.example.repo.dto.FileLockRequest;
import com.example.repo.dto.FileOperationRequest;
import com.example.repo.dto.PageRequest;
import com.example.repo.dto.PageResponse;
import com.example.repo.entity.FileChange;
import com.example.repo.entity.FileIndex;
import com.example.repo.entity.FileLock;
import com.example.repo.exception.ConflictException;
import com.example.repo.git.GitService;
import com.example.repo.service.FileCollaborationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 文件协作控制器
 * 工作流：创建/更新/删除（记录变更）→ 查看变更 → 勾选提交推送
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    @Autowired
    private FileCollaborationService fileService;

    @Autowired
    private GitService gitService;

    // ======================== 文件操作（仅记录变更，不提交） ========================

    /**
     * 创建文件或文件夹
     * fileType: 1-文件(默认), 2-文件夹
     * 返回变更记录（不执行 git commit）
     */
    @PostMapping("/create")
    public ApiResponse<FileChange> create(@RequestBody FileOperationRequest request) {
        try {
            FileChange change = fileService.createFileOrFolder(request);
            return ApiResponse.success(change);
        } catch (Exception e) {
            return ApiResponse.error(409, e.getMessage());
        }
    }

    /**
     * 更新文件或文件夹
     * - 文件: 更新内容，或通过 newPath 重命名/移动
     * - 文件夹: 通过 newPath 重命名/移动
     * 返回变更记录（不执行 git commit）
     */
    @PutMapping("/update")
    public ApiResponse<FileChange> update(@RequestBody FileOperationRequest request) {
        try {
            FileChange change = fileService.updateFileOrFolder(request);
            return ApiResponse.success(change);
        } catch (ConflictException e) {
            return ApiResponse.conflict(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(409, e.getMessage());
        }
    }

    /**
     * 删除文件或文件夹
     * 删除文件夹时会同时删除其下所有文件
     * 返回变更记录（不执行 git commit）
     */
    @DeleteMapping("/delete")
    public ApiResponse<FileChange> delete(@RequestParam String deployCode,@RequestParam String spaceCode,
                                          @RequestParam String filePath,
                                          @RequestParam String operator) {
        try {
            FileChange change = fileService.deleteFileOrFolder(deployCode, spaceCode, filePath, operator);
            return ApiResponse.success(change);
        } catch (Exception e) {
            return ApiResponse.error(409, e.getMessage());
        }
    }

    // ======================== 查看变更 & 提交推送 ========================

    /**
     * 查看仓库的所有未提交变更（不分页）
     * 返回变更列表，包含文件路径、变更类型（ADD/MODIFY/DELETE）
     */
    @GetMapping("/changes")
    public ApiResponse<List<FileChange>> getPendingChanges(@RequestParam String deployCode,@RequestParam String spaceCode) {
        try {
            List<FileChange> changes = fileService.getPendingChanges(deployCode, spaceCode);
            return ApiResponse.success(changes);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 分页查看仓库的未提交变更（按目录树状排序）
     * 支持分页和目录树导航，文件夹优先显示
     * 
     * @param deployCode, spaceCode 仓库ID
     * @param pageNum 页码（从1开始，默认1）
     * @param pageSize 每页大小（默认20）
     * @param parentPath 父目录路径（可选，用于浏览特定目录）
     */
    @GetMapping("/changes/page")
    public ApiResponse<PageResponse> getPendingChangesWithPage(
            @RequestParam String deployCode,@RequestParam String spaceCode,
            @RequestParam(required = false, defaultValue = "1") Integer pageNum,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String parentPath) {
        try {
            PageRequest pageRequest = new PageRequest();
            pageRequest.setPageNum(pageNum);
            pageRequest.setPageSize(pageSize);
            pageRequest.setParentPath(parentPath);
            
            PageResponse response = fileService.getPendingChangesWithPage(deployCode, spaceCode, pageRequest);
            return ApiResponse.success(response);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 提交并推送（用户勾选变更后调用）
     * - selectedChangeIds: 选中的变更 ID 列表，为空则提交全部
     * - commitMessage: Git 提交信息
     * - pushToRemote: 是否推送到远程（默认 false）
     */
    @PostMapping("/commit")
    public ApiResponse<String> commitAndPush(@RequestBody CommitRequest request) {
        try {
            String commitHash = fileService.commitAndPush(request);
            return ApiResponse.success(commitHash);
        } catch (Exception e) {
            return ApiResponse.error(409, e.getMessage());
        }
    }

    // ======================== 查询接口 ========================

    /**
     * 获取文件列表
     */
    @GetMapping("/list")
    public ApiResponse<List<FileIndex>> listFiles(@RequestParam String deployCode,@RequestParam String spaceCode,
                                                   @RequestParam(required = false) String parentPath) {
        try {
            List<FileIndex> files = fileService.getFileList(deployCode, spaceCode, parentPath);
            return ApiResponse.success(files);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 获取文件历史
     */
    @GetMapping("/history")
    public ApiResponse<List<GitService.CommitInfo>> getFileHistory(@RequestParam String deployCode,@RequestParam String spaceCode,
                                                                    @RequestParam String filePath) {
        try {
            List<GitService.CommitInfo> history = fileService.getFileHistory(deployCode, spaceCode, filePath);
            return ApiResponse.success(history);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 读取文件内容
     */
    @GetMapping("/content")
    public ApiResponse<String> readFileContent(@RequestParam String deployCode,@RequestParam String spaceCode,
                                                @RequestParam String filePath,
                                                @RequestParam(required = false) String commitHash) {
        try {
            String content = gitService.readFileContent(deployCode, spaceCode, filePath, commitHash);
            return ApiResponse.success(content != null ? content : "");
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    // ======================== 编辑锁管理 ========================

    /**
     * 获取文件编辑锁
     * 用户打开文件编辑时调用，其他用户在锁释放前无法编辑该文件
     * 默认锁超时 5 分钟
     */
    @PostMapping("/lock/acquire")
    public ApiResponse<FileLock> acquireLock(@RequestBody FileLockRequest request) {
        try {
            FileLock lock = fileService.acquireEditLock(request);
            return ApiResponse.success(lock);
        } catch (Exception e) {
            return ApiResponse.error(409, e.getMessage());
        }
    }

    /**
     * 释放文件编辑锁
     * 用户保存完成或取消编辑时调用
     */
    @PostMapping("/lock/release")
    public ApiResponse<Void> releaseLock(@RequestBody FileLockRequest request) {
        try {
            fileService.releaseEditLock(request);
            return ApiResponse.success(null);
        } catch (Exception e) {
            return ApiResponse.error(409, e.getMessage());
        }
    }

    /**
     * 查询文件编辑锁状态
     * 返回是否被锁定、锁持有者、过期时间等信息
     */
    @GetMapping("/lock/status")
    public ApiResponse<Map<String, Object>> getLockStatus(@RequestParam String deployCode,@RequestParam String spaceCode,
                                                           @RequestParam String filePath) {
        try {
            Map<String, Object> status = fileService.getEditLockStatus(deployCode, spaceCode, filePath);
            return ApiResponse.success(status);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }
}
