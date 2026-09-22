package com.wuling.product.port;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wuling.product.subject.entity.BizSubject;
import com.wuling.product.subject.mapper.BizSubjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link SubjectQueryPort} 的本地实现（第 12 期）。
 *
 * <p>单体阶段直接查本地表，零网络开销。
 *
 * <p><b>这是唯一允许引用 subject 实体的适配层</b>：
 * 依赖方的业务代码只依赖 {@link SubjectQueryPort} 接口。
 * subject 拆为独立服务后，新增一个远程实现并用条件注解切换即可。
 */
@Component
public class LocalSubjectQueryAdapter implements SubjectQueryPort {

    private final BizSubjectMapper bizSubjectMapper;

    public LocalSubjectQueryAdapter(BizSubjectMapper bizSubjectMapper) {
        this.bizSubjectMapper = bizSubjectMapper;
    }

    @Override
    public SubjectView findById(Long subjectId) {
        if (subjectId == null) {
            return null;
        }
        BizSubject subject = bizSubjectMapper.selectById(subjectId);
        if (subject == null) {
            return null;
        }
        SubjectView view = new SubjectView();
        view.setId(subject.getId());
        view.setCode(subject.getCode());
        view.setName(subject.getName());
        view.setSubjectType(subject.getSubjectType());
        return view;
    }

    @Override
    public String findName(Long subjectId) {
        SubjectView view = findById(subjectId);
        return view == null ? null : view.getName();
    }

    @Override
    public Long findInvestorOfStore(Long storeSubjectId) {
        if (storeSubjectId == null) {
            return null;
        }
        return bizSubjectMapper.selectInvestorOfStore(storeSubjectId);
    }

    @Override
    public List<Long> findStoreIdsByChannel(Long channelSubjectId) {
        if (channelSubjectId == null) {
            return List.of();
        }
        List<Long> ids = bizSubjectMapper.selectStoreIdsByChannel(channelSubjectId);
        return ids == null ? List.of() : ids;
    }

    @Override
    public List<Long> findStoreIdsByInvestor(Long investorSubjectId) {
        if (investorSubjectId == null) {
            return List.of();
        }
        List<Long> ids = bizSubjectMapper.selectStoreIdsByInvestor(investorSubjectId);
        return ids == null ? List.of() : ids;
    }

    @Override
    public Long findFirstByType(String subjectType) {
        if (subjectType == null) {
            return null;
        }
        BizSubject subject = bizSubjectMapper.selectOne(new LambdaQueryWrapper<BizSubject>()
                .eq(BizSubject::getSubjectType, subjectType).last("limit 1"));
        return subject == null ? null : subject.getId();
    }

    @Override
    public Map<Long, String> findNames(List<Long> subjectIds) {
        if (subjectIds == null || subjectIds.isEmpty()) {
            return Map.of();
        }
        // 批量查询，避免 N+1
        List<BizSubject> subjects = bizSubjectMapper.selectList(
                new LambdaQueryWrapper<BizSubject>().in(BizSubject::getId, subjectIds));
        Map<Long, String> result = new HashMap<>();
        for (BizSubject s : subjects) {
            result.put(s.getId(), s.getName());
        }
        return result;
    }
}
