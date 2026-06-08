package com.example.repo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.repo.entity.FileChange;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文件变更记录 Mapper
 */
@Mapper
public interface FileChangeMapper extends BaseMapper<FileChange> {
    
    /**
     * 分页查询未提交变更（支持目录过滤和排序）
     * SQL定义在 resources/mapper/FileChangeMapper.xml
     * 
     * @param deployCode 部署编码
     * @param spaceCode 空间编码
     * @param parentPath 父目录路径（可选），如果指定则只查询该目录下的直接子项
     * @param page 分页对象
     * @return 分页结果
     */
    IPage<FileChange> selectPendingChangesWithPage(
            @Param("deployCode") String deployCode,
            @Param("spaceCode") String spaceCode,
            @Param("parentPath") String parentPath,
            Page<FileChange> page
    );
    
    /**
     * 批量查询文件类型（用于判断是否为文件夹）
     * SQL定义在 resources/mapper/FileChangeMapper.xml
     * 
     * @param deployCode 部署编码
     * @param spaceCode 空间编码
     * @param filePaths 文件路径列表
     * @return 文件索引列表
     */
    List<FileTypeResult> selectFileTypes(
            @Param("deployCode") String deployCode,
            @Param("spaceCode") String spaceCode,
            @Param("filePaths") List<String> filePaths
    );
    
    /**
     * 文件类型查询结果
     */
    class FileTypeResult {
        private String filePath;
        private Integer fileType;
        
        public String getFilePath() {
            return filePath;
        }
        
        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }
        
        public Integer getFileType() {
            return fileType;
        }
        
        public void setFileType(Integer fileType) {
            this.fileType = fileType;
        }
    }
}
