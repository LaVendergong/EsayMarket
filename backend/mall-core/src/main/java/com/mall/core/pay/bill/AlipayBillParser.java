package com.mall.core.pay.bill;

import com.mall.core.enums.PayChannel;
import org.springframework.stereotype.Component;

/**
 * 支付宝对账单解析（业务明细 CSV）。
 *
 * <p>支付宝账单前几行是账号/日期等表头，真正的列名行里含「商户订单号」，从中按表头名取列。
 * 金额列取「订单金额」（元），退款行通过「退款批次号/请求号」判定。
 */
@Component
public class AlipayBillParser extends HeaderMappedBillParser {

    @Override
    public PayChannel channel() {
        return PayChannel.ALIPAY_WAP;
    }

    @Override
    protected String[] outTradeNoHeaders() {
        return new String[]{"商户订单号"};
    }

    @Override
    protected String[] channelTradeNoHeaders() {
        return new String[]{"支付宝交易号"};
    }

    @Override
    protected String[] amountHeaders() {
        return new String[]{"订单金额"};
    }

    @Override
    protected String[] refundHeaders() {
        return new String[]{"退款批次号/请求号", "退款金额"};
    }
}
