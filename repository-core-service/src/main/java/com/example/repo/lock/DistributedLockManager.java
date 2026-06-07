package com.example.repo.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 三级锁机制实现
 * L1: 仓库级锁 - 防止 Git 索引文件损坏
 * L2: 文件级锁 - 防止业务层面的并发写入
 */
@Component
public class DistributedLockManager {

    @Autowired
    private RedissonClient redissonClient;

    private static final String LOCK_PREFIX = "repo:lock:";
    private static final String REPO_LOCK_SUFFIX = ":repo";
    private static final String FILE_LOCK_SUFFIX = ":file:";

    /**
     * 获取仓库级锁 (L1)
     * @param repoId 仓库 ID
     * @param waitTime 等待时间 (秒)
     * @param leaseTime 租约时间 (秒)
     * @return 锁对象，null 表示获取失败
     */
    public RLock tryGetRepoLock(Long repoId, long waitTime, long leaseTime) {
        String lockKey = LOCK_PREFIX + repoId + REPO_LOCK_SUFFIX;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS)) {
                return lock;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /**
     * 获取文件级锁 (L2)
     * @param repoId 仓库 ID
     * @param filePath 文件路径
     * @param waitTime 等待时间 (秒)
     * @param leaseTime 租约时间 (秒)
     * @return 锁对象，null 表示获取失败
     */
    public RLock tryGetFileLock(Long repoId, String filePath, long waitTime, long leaseTime) {
        String lockKey = LOCK_PREFIX + repoId + FILE_LOCK_SUFFIX + filePath.replace("/", "_");
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(waitTime, leaseTime, TimeUnit.SECONDS)) {
                return lock;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /**
     * 释放锁
     * @param lock 锁对象
     */
    public void releaseLock(RLock lock) {
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
