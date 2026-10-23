package com.mall.common.result;

import lombok.Getter;

/**
 * 业务返回码
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    FAILED(500, "操作失败"),
    VALIDATE_FAILED(400, "参数校验失败"),
    UNAUTHORIZED(401, "暂未登录或 token 已过期"),
    FORBIDDEN(403, "没有相关权限"),
    NOT_FOUND(404, "资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),

    USERNAME_EXISTS(1001, "用户名已存在"),
    USER_NOT_FOUND(1002, "用户不存在"),
    PASSWORD_ERROR(1003, "用户名或密码错误"),
    ACCOUNT_DISABLED(1004, "账号已被禁用"),
    LOGIN_TOO_MANY_ATTEMPTS(1005, "登录失败次数过多，请稍后再试"),

    PRODUCT_NOT_FOUND(2001, "商品不存在"),
    PRODUCT_OFF_SHELF(2002, "商品已下架"),
    STOCK_NOT_ENOUGH(2003, "库存不足"),
    CART_EMPTY(2004, "购物车为空"),

    CATEGORY_NOT_FOUND(2005, "分类不存在"),
    CATEGORY_HAS_DATA(2006, "分类下存在子分类或商品，无法删除"),
    SKU_NOT_FOUND(2007, "SKU 不存在"),
    CATEGORY_NAME_EXISTS(2008, "同级下已存在同名分类"),
    CART_ITEM_NOT_FOUND(2009, "购物车条目不存在"),
    ADDRESS_REQUIRED(2010, "请先设置收货地址"),

    ORDER_NOT_FOUND(3001, "订单不存在"),
    ORDER_STATUS_ERROR(3002, "当前订单状态不允许该操作"),
    AFTER_SALE_NOT_FOUND(3003, "售后单不存在"),
    AFTER_SALE_STATUS_ERROR(3004, "当前售后状态不允许该操作"),
    AFTER_SALE_EXISTS(3005, "该订单已有进行中的售后，请等商家处理"),
    AFTER_SALE_NOT_ALLOWED(3006, "当前订单状态不支持申请售后（需已支付/已发货/已完成）"),
    PAY_CHANNEL_UNAVAILABLE(4001, "支付渠道不可用或未配置"),
    PAYMENT_NOT_FOUND(4002, "支付单不存在"),
    PAYMENT_STATUS_ERROR(4003, "当前支付状态不允许该操作"),
    PAY_AMOUNT_MISMATCH(4004, "支付金额与订单不一致"),
    PAY_NOTIFY_INVALID(4005, "支付回调校验失败"),
    REFUND_FAILED(4006, "渠道退款失败"),
    REFUND_NOT_SUPPORTED(4007, "原支付渠道不支持自动退款"),
    PAY_QUERY_NOT_SUPPORTED(4008, "该渠道不支持支付查询"),
    COUPON_NOT_FOUND(7001, "优惠券不存在"),
    COUPON_UNAVAILABLE(7002, "优惠券不可用（已使用或已过期）"),
    COUPON_THRESHOLD_NOT_MET(7003, "未达到优惠券使用门槛"),
    COUPON_SOLD_OUT(7004, "优惠券已领完"),
    COUPON_ALREADY_RECEIVED(7005, "该优惠券已领取过"),
    FREIGHT_TEMPLATE_NOT_FOUND(7006, "运费模板不存在"),
    MIXED_ORDER_NOT_ALLOWED(7101, "实物与虚拟商品不能一起下单，请分开结算"),
    VIRTUAL_GOODS_CONFIG_ERROR(7102, "虚拟商品配置不完整（发放方式/外链地址/卡密库存）"),
    DIGITAL_GRANT_NOT_FOUND(7103, "凭证不存在"),
    DIGITAL_GRANT_UNAVAILABLE(7104, "凭证不可用（已作废或发放失败）"),
    CARD_KEY_REQUIRED(7105, "卡密类商品必须先导入卡密"),
    REVIEW_ALREADY_SUBMITTED(7201, "该商品已评价过"),
    REVIEW_NOT_FOUND(7202, "评价不存在"),
    UPLOAD_INVALID(7301, "图片上传失败");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
