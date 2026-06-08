package com.example.repo.controller;

import com.example.repo.dto.ApiResponse;
import com.example.repo.entity.Repository;
import com.example.repo.git.GitService;
import com.example.repo.mapper.RepositoryMapper;
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

    /**
     * 创建仓库
     */
    @PostMapping
    public ApiResponse<Repository> createRepository(@RequestBody Repository repository) {
        try {


            // 保存仓库信息
            repositoryMapper.insert(repository);

            // 初始化 Git 仓库
            gitService.initRepo(repository.getDeployCode(),repository.getSpaceCode());

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
    @GetMapping("/{deployCode}/{spaceCode}")
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
    @GetMapping("/{deployCode}/{spaceCode}/branch")
    public ApiResponse<String> getCurrentBranch(@PathVariable String deployCode,@PathVariable String spaceCode,@PathVariable Long id) {
        try {
            String branch = gitService.getCurrentBranch(deployCode,spaceCode);
            return ApiResponse.success(branch);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 创建分支
     */
    @PostMapping("/{deployCode}/{spaceCode}/branches")
    public ApiResponse<Void> createBranch(@PathVariable String deployCode, @PathVariable String spaceCode,
                                          @RequestParam String branchName) {
        try {
            gitService.createBranch(deployCode,spaceCode, branchName);
            return ApiResponse.success(null);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }

    /**
     * 切换分支
     */
    @PostMapping("/{deployCode}/{spaceCode}/checkout")
    public ApiResponse<Void> checkoutBranch(@PathVariable String deployCode, @PathVariable String spaceCode,
                                             @RequestParam String branchName) {
        try {
            gitService.checkoutBranch(deployCode,spaceCode, branchName);
            return ApiResponse.success(null);
        } catch (Exception e) {
            return ApiResponse.error(500, e.getMessage());
        }
    }
}
