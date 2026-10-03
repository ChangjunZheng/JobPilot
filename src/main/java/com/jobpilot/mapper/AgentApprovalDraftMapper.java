package com.jobpilot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobpilot.domain.AgentApprovalDraftEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * HITL 审批草稿。
 * <p>
 * <b>不使用 {@code @InterceptorIgnore}</b>：审批走 HTTP 接口，在请求线程上执行，
 * 租户拦截器照常注入 {@code user_id}——跨租户审批因此被自然挡掉（查不到别人的行）。
 */
public interface AgentApprovalDraftMapper extends BaseMapper<AgentApprovalDraftEntity> {

    /**
     * 幂等防线①：审批时先锁行。
     * <p>
     * 并发两个 approve 会串行化，后到者重读时发现 status 已非 PENDING 就不重复执行副作用。
     * <b>必须在事务内调用</b>，锁随审批事务提交而释放。
     * <p>
     * SELECT 语句本身仍会被租户拦截器加上 {@code user_id} 条件——这正是我们要的：
     * 拿别人的草稿去锁会直接查不到，对外表现为「不存在」。
     */
    @Select("SELECT * FROM agent_approval_draft WHERE id = #{id} FOR UPDATE")
    AgentApprovalDraftEntity selectByIdForUpdate(@Param("id") String id);
}
