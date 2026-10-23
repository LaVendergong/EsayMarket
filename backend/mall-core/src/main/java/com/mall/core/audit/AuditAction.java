package com.mall.core.audit;

import java.util.Map;

/**
 * 审计动作。
 *
 * <p>用常量串而不是枚举：这些值是写进历史数据的，加动作只需要加常量，不影响已有记录。
 */
public final class AuditAction {

    public static final String PRODUCT_UPDATE = "PRODUCT_UPDATE";
    public static final String PRODUCT_DELETE = "PRODUCT_DELETE";
    public static final String ORDER_SHIP = "ORDER_SHIP";
    public static final String ORDER_CLOSE = "ORDER_CLOSE";
    public static final String ORDER_GRANT = "ORDER_GRANT";
    public static final String AFTER_SALE_APPROVE = "AFTER_SALE_APPROVE";
    public static final String AFTER_SALE_REJECT = "AFTER_SALE_REJECT";
    public static final String REVIEW_HIDE = "REVIEW_HIDE";
    public static final String REVIEW_RESTORE = "REVIEW_RESTORE";
    public static final String CARD_KEY_IMPORT = "CARD_KEY_IMPORT";
    public static final String RBAC_ROLE_CREATE = "RBAC_ROLE_CREATE";
    public static final String RBAC_ROLE_UPDATE = "RBAC_ROLE_UPDATE";
    public static final String RBAC_ROLE_DELETE = "RBAC_ROLE_DELETE";
    public static final String RBAC_ROLE_PERM = "RBAC_ROLE_PERM";
    public static final String RBAC_USER_ROLE = "RBAC_USER_ROLE";

    // Map.of 最多 10 对键值，超过就会在编译期报错；这里用 ofEntries
    private static final Map<String, String> TEXT = Map.ofEntries(
            Map.entry(PRODUCT_UPDATE, "修改商品"),
            Map.entry(PRODUCT_DELETE, "删除商品"),
            Map.entry(ORDER_SHIP, "订单发货"),
            Map.entry(ORDER_CLOSE, "关闭订单"),
            Map.entry(ORDER_GRANT, "补发凭证"),
            Map.entry(AFTER_SALE_APPROVE, "同意售后"),
            Map.entry(AFTER_SALE_REJECT, "拒绝售后"),
            Map.entry(REVIEW_HIDE, "隐藏评价"),
            Map.entry(REVIEW_RESTORE, "恢复评价"),
            Map.entry(CARD_KEY_IMPORT, "导入卡密"),
            Map.entry(RBAC_ROLE_CREATE, "新增角色"),
            Map.entry(RBAC_ROLE_UPDATE, "修改角色"),
            Map.entry(RBAC_ROLE_DELETE, "删除角色"),
            Map.entry(RBAC_ROLE_PERM, "调整角色权限"),
            Map.entry(RBAC_USER_ROLE, "调整管理员角色")
    );

    private AuditAction() {
    }

    /** 未知 code 原样返回，别吞成空 */
    public static String textOf(String action) {
        return action == null ? "" : TEXT.getOrDefault(action, action);
    }
}
