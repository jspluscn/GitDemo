package com.example.repo.git;

import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Git 操作封装类 - 基于 JGit
 */
@Component
public class GitService {

    @Value("${repo.storage.nas-base-path:/mnt/nas/git-repositories}")
    private String nasBasePath;

    /**
     * 初始化仓库
     */
    public Git initRepo(Long repoId) throws GitAPIException {
        String repoPath = getRepoPath(repoId);
        File repoDir = new File(repoPath);
        if (!repoDir.exists()) {
            repoDir.mkdirs();
        }
        return Git.init().setDirectory(repoDir).call();
    }

    /**
     * 打开仓库
     */
    public Git openRepo(Long repoId) throws IOException {
        String repoPath = getRepoPath(repoId);
        File repoDir = new File(repoPath);
        return Git.open(repoDir);
    }

    /**
     * 添加文件到暂存区
     */
    public void addFile(Long repoId, String filePath) throws IOException, GitAPIException {
        try (Git git = openRepo(repoId)) {
            git.add().addFilepattern(filePath).call();
        }
    }

    /**
     * 提交变更
     */
    public String commit(Long repoId, String message, String authorName, String authorEmail) 
            throws IOException, GitAPIException {
        try (Git git = openRepo(repoId)) {
            PersonIdent ident = new PersonIdent(authorName, authorEmail);
            RevCommit revCommit = git.commit()
                    .setAuthor(ident)
                    .setMessage(message)
                    .call();
            return revCommit.getId().getName();
        }
    }

    /**
     * 获取文件历史
     */
    public List<CommitInfo> getFileHistory(Long repoId, String filePath) throws IOException, GitAPIException {
        List<CommitInfo> history = new ArrayList<>();
        try (Git git = openRepo(repoId)) {
            Iterable<RevCommit> logs = git.log().addPath(filePath).call();
            for (RevCommit commit : logs) {
                CommitInfo info = new CommitInfo();
                info.setCommitHash(commit.getId().getName());
                info.setAuthorName(commit.getAuthorIdent().getName());
                info.setAuthorEmail(commit.getAuthorIdent().getEmailAddress());
                info.setMessage(commit.getShortMessage());
                info.setCommitTime(LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(commit.getCommitTime()), ZoneId.systemDefault()));                history.add(info);
            }
        }
        return history;
    }

    /**
     * 读取文件内容
     */
    public String readFileContent(Long repoId, String filePath, String commitHash) throws IOException {
        try (Git git = openRepo(repoId)) {
            if (commitHash == null || commitHash.isEmpty()) {
                // 读取工作区文件
                File file = new File(getRepoPath(repoId), filePath);
                if (file.exists()) {
                    return java.nio.file.Files.readString(file.toPath());
                }
                return null;
            }
        }
        return null;
    }

    /**
     * 写入文件内容
     */
    public void writeFileContent(Long repoId, String filePath, String content) throws IOException {
        File file = new File(getRepoPath(repoId), filePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        Files.writeString(file.toPath(), content);
    }

    /**
     * 删除文件
     */
    public void deleteFile(Long repoId, String filePath) throws IOException, GitAPIException {
        try (Git git = openRepo(repoId)) {
            File file = new File(getRepoPath(repoId), filePath);
            if (file.exists()) {
                file.delete();
                git.rm().addFilepattern(filePath).call();
            }
        }
    }

    /**
     * 创建分支
     */
    public void createBranch(Long repoId, String branchName) throws IOException, GitAPIException {
        try (Git git = openRepo(repoId)) {
            git.branchCreate().setName(branchName).call();
        }
    }

    /**
     * 切换分支
     */
    public void checkoutBranch(Long repoId, String branchName) throws IOException, GitAPIException {
        try (Git git = openRepo(repoId)) {
            git.checkout().setName(branchName).call();
        }
    }

    /**
     * 获取当前分支
     */
    public String getCurrentBranch(Long repoId) throws IOException {
        try (Git git = openRepo(repoId)) {
            return git.getRepository().getBranch();
        }
    }

    /**
     * 获取仓库路径
     */
    public String getRepoPath(Long repoId) {
        return nasBasePath + "/repo_" + repoId;
    }

    /**
     * 创建文件夹（通过创建 .gitkeep 文件让 Git 追踪）
     */
    public void createFolder(Long repoId, String folderPath) throws IOException, GitAPIException {
        File folder = new File(getRepoPath(repoId), folderPath);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        // 创建 .gitkeep 文件让 Git 可以追踪空文件夹
        File gitkeep = new File(folder, ".gitkeep");
        if (!gitkeep.exists()) {
            gitkeep.createNewFile();
        }
        try (Git git = openRepo(repoId)) {
            git.add().addFilepattern(folderPath + "/.gitkeep").call();
        }
    }

    /**
     * 删除文件夹及其下所有文件
     * @return 被删除的文件相对路径列表
     */
    public List<String> deleteFolder(Long repoId, String folderPath) throws IOException, GitAPIException {
        List<String> deletedFiles = new ArrayList<>();
        File folder = new File(getRepoPath(repoId), folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            return deletedFiles;
        }

        // 收集所有被删除的文件路径
        try (Git git = openRepo(repoId)) {
            try (Stream<Path> paths = Files.walk(folder.toPath())) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            File f = path.toFile();
                            if (f.isFile()) {
                                // 计算相对路径
                                String relativePath = getRepoPath(repoId);
                                String rel = path.toFile().getAbsolutePath()
                                        .substring(relativePath.length() + 1)
                                        .replace("\\", "/");
                                deletedFiles.add(rel);
                                try {
                                    git.rm().addFilepattern(rel).call();
                                } catch (Exception ignored) {
                                    // 文件可能未被 Git 追踪
                                }
                            }
                            f.delete();
                        });
            }
        }
        return deletedFiles;
    }

    /**
     * 重命名/移动文件夹
     * @return 受影响的文件旧路径与新路径对
     */
    public List<String[]> renameFolder(Long repoId, String oldPath, String newPath) throws IOException, GitAPIException {
        List<String[]> affectedFiles = new ArrayList<>();
        File oldFolder = new File(getRepoPath(repoId), oldPath);
        File newFolder = new File(getRepoPath(repoId), newPath);

        if (!oldFolder.exists() || !oldFolder.isDirectory()) {
            throw new IOException("Source folder does not exist: " + oldPath);
        }

        // 创建新文件夹
        if (!newFolder.exists()) {
            newFolder.mkdirs();
        }

        String repoRoot = getRepoPath(repoId);

        try (Git git = openRepo(repoId)) {
            // 移动所有文件
            try (Stream<Path> paths = Files.walk(oldFolder.toPath())) {
                List<Path> fileList = paths.filter(Files::isRegularFile).toList();
                for (Path oldFile : fileList) {
                    String relOld = oldFile.toFile().getAbsolutePath()
                            .substring(repoRoot.length() + 1).replace("\\", "/");
                    // 计算新路径
                    String relNew = newPath + relOld.substring(oldPath.length());
                    File destFile = new File(repoRoot, relNew);
                    File destParent = destFile.getParentFile();
                    if (destParent != null && !destParent.exists()) {
                        destParent.mkdirs();
                    }
                    Files.move(oldFile, destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                    // Git: 删除旧文件，添加新文件
                    try { git.rm().addFilepattern(relOld).call(); } catch (Exception ignored) {}
                    git.add().addFilepattern(relNew).call();

                    affectedFiles.add(new String[]{relOld, relNew});
                }
            }
            // 删除旧的空目录
            try (Stream<Path> dirs = Files.walk(oldFolder.toPath())) {
                dirs.sorted(Comparator.reverseOrder())
                        .filter(Files::isDirectory)
                        .forEach(p -> p.toFile().delete());
            }
            oldFolder.delete();
        }
        return affectedFiles;
    }

    /**
     * 重命名/移动单个文件
     */
    public void renameFile(Long repoId, String oldPath, String newPath) throws IOException, GitAPIException {
        String repoRoot = getRepoPath(repoId);
        File oldFile = new File(repoRoot, oldPath);
        File newFile = new File(repoRoot, newPath);

        if (!oldFile.exists()) {
            throw new IOException("Source file does not exist: " + oldPath);
        }

        File parent = newFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        Files.move(oldFile.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        try (Git git = openRepo(repoId)) {
            try { git.rm().addFilepattern(oldPath).call(); } catch (Exception ignored) {}
            git.add().addFilepattern(newPath).call();
        }
    }

    /**
     * 重置暂存区（不改变工作区）
     */
    public void resetStaged(Long repoId) throws IOException, GitAPIException {
        try (Git git = openRepo(repoId)) {
            git.reset().call();
        }
    }

    /**
     * 批量添加文件到暂存区
     */
    public void addFiles(Long repoId, List<String> filePaths) throws IOException, GitAPIException {
        try (Git git = openRepo(repoId)) {
            AddCommand add = git.add();
            for (String path : filePaths) {
                add.addFilepattern(path);
            }
            add.call();
        }
    }

    /**
     * 批量从暂存区删除文件（用于 DELETE 类型变更）
     */
    public void rmFiles(Long repoId, List<String> filePaths) throws IOException, GitAPIException {
        try (Git git = openRepo(repoId)) {
            RmCommand rm = git.rm();
            for (String path : filePaths) {
                rm.addFilepattern(path);
            }
            rm.call();
        }
    }

    /**
     * 提交当前暂存区的所有变更
     */
    public String commitStaged(Long repoId, String message,
                               String authorName, String authorEmail)
            throws IOException, GitAPIException {
        try (Git git = openRepo(repoId)) {
            PersonIdent ident = new PersonIdent(authorName, authorEmail);
            RevCommit revCommit = git.commit()
                    .setAuthor(ident)
                    .setMessage(message)
                    .call();
            return revCommit.getId().getName();
        }
    }

    /**
     * 推送到远程仓库
     */
    public void push(Long repoId) throws IOException, GitAPIException {
        try (Git git = openRepo(repoId)) {
            git.push().call();
        }
    }

    /**
     * 提交信息 DTO
     */
    @lombok.Data
    public static class CommitInfo {
        private String commitHash;
        private String authorName;
        private String authorEmail;
        private String message;
        private LocalDateTime commitTime;
    }
}
