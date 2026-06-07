package com.example.repo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.repo.entity.NasStorageStatus;
import org.apache.ibatis.annotations.Mapper;

/**
 * NAS 存储状态 Mapper 接口
 */
@Mapper
public interface NasStorageStatusMapper extends BaseMapper<NasStorageStatus> {
}
