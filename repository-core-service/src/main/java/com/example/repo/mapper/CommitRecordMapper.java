package com.example.repo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.repo.entity.CommitRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 提交记录 Mapper 接口
 */
@Mapper
public interface CommitRecordMapper extends BaseMapper<CommitRecord> {
}
