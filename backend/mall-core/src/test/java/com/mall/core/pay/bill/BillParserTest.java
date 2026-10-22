package com.mall.core.pay.bill;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三个渠道账单解析器。
 *
 * <p>真实账单拿不到（没有商户资质），所以用**按公开格式构造的样本**把列映射钉住：
 * 表头名 → 列号、反引号/BOM/汇总行、退款行的识别。上线前拿真实文件核对时，
 * 如果格式有偏差，这几条测试最先挂。
 */
class BillParserTest {

    @Test
    void normalized_parsesRowsAndSkipsRepeatedHeader() {
        String csv = """
                out_trade_no,channel_trade_no,amount,status,biz_type
                P1,T1,100.00,SUCCESS,PAY
                out_trade_no,channel_trade_no,amount,status,biz_type
                P2,T2,55.50,SUCCESS,PAY
                """;

        List<BillLine> lines = new NormalizedBillParser().parse(csv);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getOutTradeNo()).isEqualTo("P1");
        assertThat(lines.get(0).getAmount()).isEqualByComparingTo("100.00");
        assertThat(lines.get(1).getAmount()).isEqualByComparingTo("55.50");
    }

    @Test
    void alipay_findsHeaderRowAndMapsByName() {
        String csv = """
                支付宝业务明细查询
                账号:xxx  起始日期:2026-10-21
                ----------------------------------------------------
                商户订单号,支付宝交易号,业务类型,商品名称,创建时间,完成时间,门店编号,门店名称,操作员,终端号,对方账户,订单金额,商家实收,退款批次号/请求号,退款金额
                P1,2026102122001,交易,商品,2026-10-21 10:00:00,2026-10-21 10:00:05,,,,,,100.00,100.00,,0.00
                P2,2026102122002,交易退款,商品,2026-10-21 11:00:00,2026-10-21 11:00:05,,,,,,55.00,55.00,RB123,55.00
                #汇总,2笔,,,,,,,,,,,,100.00
                """;

        List<BillLine> lines = new AlipayBillParser().parse(csv);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getOutTradeNo()).isEqualTo("P1");
        assertThat(lines.get(0).getChannelTradeNo()).isEqualTo("2026102122001");
        assertThat(lines.get(0).getAmount()).isEqualByComparingTo("100.00");
        assertThat(lines.get(0).getBizType()).isEqualTo("PAY");
        // 有退款批次号/退款金额的行是退款，v1 对账会跳过它，绝不能当成收款
        assertThat(lines.get(1).getBizType()).isEqualTo("REFUND");
    }

    @Test
    void wechat_stripsBackticksAndReadsTradeState() {
        String csv = """
                交易时间,公众账号ID,商户号,特约商户号,设备号,微信订单号,商户订单号,用户标识,交易类型,交易状态,付款银行,货币种类,应结订单金额,代金券金额,微信退款单号,商户退款单号,退款金额,充值券退款金额,退款类型,退款状态,商品名称,商户数据包,手续费,费率,订单金额
                `2026-10-21 10:00:00,`wx123,`1900000001,`0,`,`4200001,`P1,`oUser,`NATIVE,`SUCCESS,`CMB,`CNY,`100.00,`0.00,`,`0,`0.00,`0.00,`,`,`商品,`,`,`0.60,`0.60%,`100.00
                `2026-10-21 11:00:00,`wx123,`1900000001,`0,`,`4200002,`P2,`oUser,`NATIVE,`SUCCESS,`CMB,`CNY,`55.00,`0.00,`5000001,`RF1,`55.00,`0.00,`ORIGINAL,`SUCCESS,`商品,`,`,`0.33,`0.60%,`55.00
                """;

        List<BillLine> lines = new WechatBillParser().parse(csv);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getOutTradeNo()).isEqualTo("P1");
        assertThat(lines.get(0).getChannelTradeNo()).isEqualTo("4200001");
        assertThat(lines.get(0).getStatus()).isEqualTo("SUCCESS");
        assertThat(lines.get(0).getAmount()).isEqualByComparingTo("100.00");
        assertThat(lines.get(0).getBizType()).isEqualTo("PAY");
        // 带退款单号的行是退款，不能被当成收款参与支付对账
        assertThat(lines.get(1).getBizType()).isEqualTo("REFUND");
    }

    @Test
    void parser_returnsEmptyWhenHeaderIsMissing() {
        assertThat(new AlipayBillParser().parse("随便什么内容\n没有表头")).isEmpty();
        assertThat(new NormalizedBillParser().parse("a,b,c\n1,2,3")).isEmpty();
    }

    @Test
    void parser_skipsUnparsableAmount() {
        String csv = """
                out_trade_no,channel_trade_no,amount,status,biz_type
                P1,T1,not-a-number,SUCCESS,PAY
                P2,T2,10.00,SUCCESS,PAY
                """;

        List<BillLine> lines = new NormalizedBillParser().parse(csv);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).getOutTradeNo()).isEqualTo("P2");
        assertThat(lines.get(0).getAmount()).isEqualByComparingTo(BigDecimal.TEN);
    }
}
