package com.wuling.marketing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wuling.common.api.PageResult;
import com.wuling.common.api.ResultCode;
import com.wuling.common.exception.BusinessException;
import com.wuling.marketing.entity.Comment;
import com.wuling.marketing.mapper.CommentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 评论：提交 / 审核 */
@Service
public class CommentService {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    private final CommentMapper commentMapper;

    public CommentService(CommentMapper commentMapper) {
        this.commentMapper = commentMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public Comment submit(Long orderId, Long userId, Integer rating, String content, String images) {
        Long exists = commentMapper.selectCount(new LambdaQueryWrapper<Comment>()
                .eq(Comment::getOrderId, orderId));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该订单已评价");
        }
        Comment comment = new Comment();
        comment.setOrderId(orderId);
        comment.setUserId(userId);
        comment.setRating(rating == null ? 5 : rating);
        comment.setContent(content);
        comment.setImages(images);
        comment.setStatus(PENDING);
        commentMapper.insert(comment);
        return comment;
    }

    @Transactional(rollbackFor = Exception.class)
    public Comment review(Long id, boolean approve, String reason) {
        Comment comment = commentMapper.selectById(id);
        if (comment == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "评论不存在");
        }
        if (!PENDING.equals(comment.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该评论已审核，不能重复操作");
        }
        Comment patch = new Comment();
        patch.setId(id);
        patch.setStatus(approve ? APPROVED : REJECTED);
        patch.setReviewTime(LocalDateTime.now());
        commentMapper.updateById(patch);
        comment.setStatus(patch.getStatus());
        return comment;
    }

    public PageResult<Comment> page(long current, long size, String status) {
        LambdaQueryWrapper<Comment> query = new LambdaQueryWrapper<Comment>().orderByDesc(Comment::getId);
        if (status != null && !status.isBlank()) {
            query.eq(Comment::getStatus, status.toUpperCase());
        }
        Page<Comment> page = commentMapper.selectPage(new Page<>(current, size), query);
        return PageResult.of(page.getRecords(), page.getCurrent(), page.getSize(), page.getTotal());
    }
}
