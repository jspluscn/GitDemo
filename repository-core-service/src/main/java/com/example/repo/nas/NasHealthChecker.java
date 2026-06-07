package com.example.repo.nas;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NAS 存储健康检查服务
 */
@Component
public class NasHealthChecker {

    @Value("${repo.storage.nas-base-path:/mnt/nas/git-repositories}")
    private String nasBasePath;

    @Value("${repo.storage.health-check-interval:60000}")
    private long healthCheckInterval;

    private final AtomicBoolean isHealthy = new AtomicBoolean(true);

    /**
     * 定期检查 NAS 健康状态
     */
    @Scheduled(fixedRateString = "${repo.storage.health-check-interval:60000}")
    public void checkHealth() {
        File nasDir = new File(nasBasePath);
        boolean healthy = nasDir.exists() && nasDir.canRead() && nasDir.canWrite();
        
        if (healthy != isHealthy.get()) {
            isHealthy.set(healthy);
            if (healthy) {
                System.out.println("[NAS Health] NAS storage recovered: " + nasBasePath);
            } else {
                System.err.println("[NAS Health] NAS storage unavailable: " + nasBasePath);
            }
        }
    }

    /**
     * 获取当前健康状态
     */
    public boolean isHealthy() {
        return isHealthy.get();
    }

    /**
     * 获取可用空间 (字节)
     */
    public long getFreeSpace() {
        File nasDir = new File(nasBasePath);
        return nasDir.getFreeSpace();
    }

    /**
     * 获取总空间 (字节)
     */
    public long getTotalSpace() {
        File nasDir = new File(nasBasePath);
        return nasDir.getTotalSpace();
    }

    /**
     * 验证 NAS 路径是否可写
     */
    public boolean validateWriteAccess() throws IOException {
        File testDir = new File(nasBasePath, ".health_check");
        try {
            if (!testDir.exists()) {
                testDir.mkdirs();
            }
            File testFile = new File(testDir, "test_" + System.currentTimeMillis());
            testFile.createNewFile();
            testFile.delete();
            return true;
        } catch (IOException e) {
            throw new IOException("NAS write access validation failed", e);
        } finally {
            testDir.delete();
        }
    }
}
