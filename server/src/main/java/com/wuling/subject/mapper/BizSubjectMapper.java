package com.wuling.subject.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wuling.subject.entity.BizSubject;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface BizSubjectMapper extends BaseMapper<BizSubject> {

    /** 门店绑定的投资人主体 ID（本期单店单投资人） */
    @Select("select investor_subject_id from store_profile where subject_id = #{storeSubjectId} and deleted = 0 limit 1")
    Long selectInvestorOfStore(@Param("storeSubjectId") Long storeSubjectId);

    /** 渠道绑定的门店主体 ID 列表 */
    @Select("select store_subject_id from channel_store where channel_subject_id = #{channelSubjectId} and deleted = 0")
    List<Long> selectStoreIdsByChannel(@Param("channelSubjectId") Long channelSubjectId);

    /** 投资人投资的（绑定关系所在）门店主体 ID 列表 */
    @Select("select subject_id from store_profile where investor_subject_id = #{investorSubjectId} and deleted = 0")
    List<Long> selectStoreIdsByInvestor(@Param("investorSubjectId") Long investorSubjectId);
}
