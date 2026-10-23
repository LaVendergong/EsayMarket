package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.result.PageResult;
import com.mall.core.audit.AuditAction;
import com.mall.core.entity.AdminAuditLog;
import com.mall.core.entity.User;
import com.mall.core.mapper.AdminAuditLogMapper;
import com.mall.core.mapper.UserMapper;
import com.mall.core.model.vo.AdminAuditLogVO;
import com.mall.core.security.UserContext;
import com.mall.core.service.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private static final long MAX_PAGE_SIZE = 100L;
    private static final int DETAIL_MAX = 500;

    private final AdminAuditLogMapper auditMapper;
    private final UserMapper userMapper;

    @Override
    public void record(String action, String targetType, Long targetId, String detail) {
        try {
            Long adminId = UserContext.getUserId();
            if (adminId == null) {
                // 定时任务 / 匿名入口没有操作人，记了也没意义
                return;
            }
            AdminAuditLog row = new AdminAuditLog();
            row.setAdminId(adminId);
            row.setAdminName(adminNameOf(adminId));
            row.setAction(action);
            row.setTargetType(targetType);
            row.setTargetId(targetId);
            row.setDetail(truncate(detail));
            row.setIp(clientIp());
            auditMapper.insert(row);
        } catch (Exception e) {
            // 审计失败绝不能让业务回滚
            log.warn("[audit] record failed: action={} target={}/{}", action, targetType, targetId, e);
        }
    }

    @Override
    public PageResult<AdminAuditLogVO> page(long pageNum, long pageSize, String action) {
        long pn = Math.max(pageNum, 1);
        long ps = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        Page<AdminAuditLog> page = auditMapper.selectPage(new Page<>(pn, ps),
                Wrappers.<AdminAuditLog>lambdaQuery()
                        .eq(StringUtils.hasText(action), AdminAuditLog::getAction, action)
                        .orderByDesc(AdminAuditLog::getId));
        List<AdminAuditLogVO> list = page.getRecords().stream().map(r -> {
            AdminAuditLogVO vo = new AdminAuditLogVO();
            vo.setId(r.getId());
            vo.setAdminId(r.getAdminId());
            vo.setAdminName(r.getAdminName());
            vo.setAction(r.getAction());
            vo.setActionText(AuditAction.textOf(r.getAction()));
            vo.setTargetType(r.getTargetType());
            vo.setTargetId(r.getTargetId());
            vo.setDetail(r.getDetail());
            vo.setIp(r.getIp());
            vo.setCreateTime(r.getCreateTime());
            return vo;
        }).toList();
        return PageResult.of(page.getTotal(), pn, ps, list);
    }

    private String adminNameOf(Long adminId) {
        User u = userMapper.selectById(adminId);
        if (u == null) {
            return null;
        }
        return StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername();
    }

    private static String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= DETAIL_MAX ? detail : detail.substring(0, DETAIL_MAX);
    }

    /** 取真实来源 IP：走 nginx 时 remoteAddr 是代理地址，优先 X-Forwarded-For 的第一段 */
    private static String clientIp() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes sra)) {
            return null;
        }
        HttpServletRequest req = sra.getRequest();
        String xff = req.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
