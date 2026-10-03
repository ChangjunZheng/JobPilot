package com.jobpilot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobpilot.domain.AgentTraceStepEntity;

/** run 每步追踪；同 {@link AgentTraceMapper}，受租户拦截器保护，不使用 @InterceptorIgnore */
public interface AgentTraceStepMapper extends BaseMapper<AgentTraceStepEntity> {
}
