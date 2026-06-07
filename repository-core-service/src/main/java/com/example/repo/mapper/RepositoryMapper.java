package com.example.repo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.repo.entity.Repository;
import org.apache.ibatis.annotations.Mapper;

/**
 * 仓库 Mapper 接口
 */
@Mapper
public interface RepositoryMapper extends BaseMapper<Repository> {
}
