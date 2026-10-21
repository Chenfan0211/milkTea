package com.wuling.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.user.entity.TongueReport;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface TongueReportMapper extends BaseMapper<TongueReport> {

    /**
     * 本人最近 6 个月、未删除摘要列表。
     */
    @Select("select * from tongue_report "
            + "where user_id = #{userId} and create_time >= #{since} and deleted = 0 "
            + "order by create_time desc, id desc")
    List<TongueReport> selectRecentByUser(@Param("userId") Long userId,
                                          @Param("since") LocalDateTime since);

    /**
     * 本人最近 6 个月、未删除单条记录。
     *
     * <p>使用 user_id 条件防止横向越权；未命中统一按不存在处理，避免泄露他人记录。
     */
    @Select("select * from tongue_report "
            + "where id = #{id} and user_id = #{userId} and create_time >= #{since} and deleted = 0 "
            + "limit 1")
    TongueReport selectOwnedRecent(@Param("id") Long id,
                                   @Param("userId") Long userId,
                                   @Param("since") LocalDateTime since);

    /** 第三方 reportId 全局唯一，用于客户端超时重试时复用既有报告。 */
    @Select("select * from tongue_report where report_id = #{reportId} limit 1")
    TongueReport selectByReportId(@Param("reportId") String reportId);
}
