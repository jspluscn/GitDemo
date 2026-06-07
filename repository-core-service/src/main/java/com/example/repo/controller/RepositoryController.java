package com.example.repo.controller;

import com.example.repo.dto.ApiResponse;
import com.example.repo.entity.Repository;
import com.example.repo.git.GitService;
import com.example.repo.mapper.RepositoryMapper;
import com.example.repo.nas.NasHealthChecker;
import org.eclipse.jgit.api.Git;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 仓库管理控制器
 */
@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {

    @Autowired
    private RepositoryMapper repositoryMapper;

    @Autowired
    private GitService gitService;

    @Autowired
    private NasHealthChecker nasHealthChecker;

    /**
     * 创建仓库
     */
    @PostMapping
    public ApiResponse<Repository> createRepository(@RequestBody Repository repository) {
        try {
            // 检查 NAS 健康状态
            if (!nasHealthChecker.isHealthy()) {
                return ApiResponse.error(503, "NAS storage is currently unavailable");
            }

            // 保存仓库信息
            repositoryMapper.insert(repository);

            // 初始化 Git 仓库
            gitService.initRepo(repository.getId());

            return ApiResponse.success(repository);
        } catch (Exception e) {
            return ApiResponse.error(500, "Failed to create repository: " + e.getMessage());
        }
    }

    /**
     * 获取仓库列表
     */
    @GetMapping
    public ApiResponse<List<Repository>> listRepositories(@RequestParam(required = false) Long ownerId) {
        try {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Repository> wrapper = 
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Repository>()
                            .eq(ownerId != null, Repository::getOwnerId, ownerId)
                            .eq(Repository::getIsDeleted, 0);
            List<Repository> repositories = repositoryMapper.selectList(wrapper);
            return ApiResponse.success(repositories);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 获取仓库详情
     */
    @GetMapping("/{id}")
    public ApiResponse<Repository> getRepository(@PathVariable Long id) {
        try {
            Repository repository = repositoryMapper.selectById(id);
            if (repository == null) {
                return ApiResponse.error(404, "Repository not found");
            }
            return ApiResponse.success(repository);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 获取当前分支
     */
    @GetMapping("/{id}/branch")
    public ApiResponse<String> getCurrentBranch(@PathVariable Long id) {
        try {
            String branch = gitService.getCurrentBranch(id);
            return ApiResponse.success(branch);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 创建分支
     */
    @PostMapping("/{id}/branches")
    public ApiResponse<Void> createBranch(@PathVariable Long id, 
                                           @RequestParam String branchName) {
        try {
            gitService.createBranch(id, branchName);
            return ApiResponse.success(null);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 切换分支
     */
    @PostMapping("/{id}/checkout")
    public ApiResponse<Void> checkoutBranch(@PathVariable Long id,
                                             @RequestParam String branchName) {
        try {
            gitService.checkoutBranch(id, branchName);
            return ApiResponse.success(null);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 检查 NAS 健康状态
     */
    @GetMapping("/health/nas")
    public ApiResponse<Object> checkNasHealth() {
        boolean healthy = nasHealthChecker.isHealthy();
        if (healthy) {
            return ApiResponse.success(java.util.Map.of(
                    "status", "healthy",
                    "freeSpace", nasHealthChecker.getFreeSpace(),
                    "totalSpace", nasHealthChecker.getTotalSpace()
            ));
        } else {
            return ApiResponse.error(503, "NAS storage is unhealthy");
        }
    }
}
