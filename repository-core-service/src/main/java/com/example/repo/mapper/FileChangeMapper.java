package com.example.repo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.repo.entity.FileChange;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件变更记录 Mapper
 */
@Mapper
public interface FileChangeMapper extends BaseMapper<FileChange> {
}
