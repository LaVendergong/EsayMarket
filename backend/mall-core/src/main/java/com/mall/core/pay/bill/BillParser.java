package com.mall.core.pay.bill;

import com.mall.core.enums.PayChannel;

import java.util.List;

/**
 * 渠道账单解析器。
 *
 * <p>为什么要 SPI：三个渠道的账单格式完全不同（支付宝 CSV 有 4 行表头、金额在第 12 列；
 * 微信账单每行以反引号开头、金额单位是分；模拟渠道用归一化格式）。对账引擎不该关心这些。
 *
 * <p><b>注意</b>：真实格式的列位置是根据公开文档写的默认值，**上线前必须拿一份真实账单核对**
 * （见 docs 的已知缺口）。归一化格式是确定可用的。
 */
public interface BillParser {

    PayChannel channel();

    /**
     * 解析账单内容。
     *
     * @param content 账单文件原文（UTF-8 或 GBK 已在读取时统一）
     * @return 支付流水行；解析不了的行跳过（记日志），不抛异常
     */
    List<BillLine> parse(String content);
}
