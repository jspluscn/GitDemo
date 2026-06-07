package com.example.repo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.repo.entity.FileLock;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件锁 Mapper 接口
 */
@Mapper
public interface FileLockMapper extends BaseMapper<FileLock> {
}
