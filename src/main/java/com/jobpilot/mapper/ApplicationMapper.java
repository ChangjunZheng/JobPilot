package com.jobpilot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobpilot.domain.ApplicationEntity;

/**
 * 投递记录。
 * <p>
 * <b>不使用 {@code @InterceptorIgnore}</b>：读写全在请求线程上，租户拦截器照常注入
 * {@code user_id}——跨租户的读、改、删因此都被自然挡住（改别人的行影响 0 行）。
 * <p>
 * 统计走 {@code selectMaps} + {@code GROUP BY status}，租户条件由拦截器注入 WHERE，
 * 每个分组天然只含当前租户。
 */
public interface ApplicationMapper extends BaseMapper<ApplicationEntity> {
}
