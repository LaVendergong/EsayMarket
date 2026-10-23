package com.mall.core.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

import java.util.Locale;
import java.util.Set;

/**
 * MyBatis-Plus 租户处理器：给所有未忽略的表自动拼 {@code tenant_id = ?}。
 *
 * <p>用框架而不是在 service 里手写条件，是因为「漏一个 where」就等于跨店数据泄露，
 * 而这种漏在代码评审里几乎看不出来。
 */
public class MallTenantHandler implements TenantLineHandler {

    /**
     * 不参与租户隔离的表。
     *
     * <p>三条理由各不相同：
     * <ul>
     *   <li>t_tenant：租户表自己；</li>
     *   <li>t_user：买家全局（同一个人在任何店都能买），否则注册/登录会变成「每店一个账号」；</li>
     *   <li>角色/权限/审计/热词/令牌：属于平台侧配置与全局数据。</li>
     * </ul>
     * 注意 t_user 不隔离不代表管理员能乱看：后台的管理员列表会显式按 tenant 过滤。
     */
    private static final Set<String> IGNORED = Set.of(
            "t_tenant",
            "t_user",
            "t_admin_role",
            "t_admin_permission",
            "t_admin_user_role",
            "t_admin_role_permission",
            "t_admin_audit_log",
            "t_search_log",
            "t_refresh_token",
            // 收货地址属于买家本人（跨店共用一份），不是商户数据
            "t_address",
            // 对账是平台财务视角，跨店汇总
            "t_reconcile_batch",
            "t_reconcile_diff",
            // 券体系暂不隔离：平台券（平台方发）和商户券（商户发）的跨店归属还没定，
            // 一刀切隔离会让「平台发的券商户看不见、商户领不到」。表上 tenant_id 已留好，
            // 等产品定了权益归属再打开只需删这两行。
            "t_coupon",
            "t_user_coupon"
    );

    @Override
    public Expression getTenantId() {
        long current = TenantContext.current();
        // 平台方（0）：读不过滤，但**写入必须有归属** —— 否则平台建的商品/券会变成 tenant_id=0 的
        // 「无主数据」，任何商户都查不到（前台会表现成"商品不存在"/"券已过期"）。默认落到 1 号店。
        return new LongValue(current == TenantContext.PLATFORM ? TenantContext.DEFAULT_TENANT : current);
    }

    @Override
    public String getTenantIdColumn() {
        return "tenant_id";
    }

    @Override
    public boolean ignoreTable(String tableName) {
        // 平台方：完全不过滤，可以跨店看
        if (TenantContext.isPlatform()) {
            return true;
        }
        return tableName != null && IGNORED.contains(tableName.toLowerCase(Locale.ROOT));
    }
}
