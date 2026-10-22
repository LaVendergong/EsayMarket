package com.mall.core.pay.bill;

import com.mall.core.enums.PayChannel;
import org.springframework.stereotype.Component;

/**
 * 微信支付对账单解析（交易账单 CSV）。
 *
 * <p>微信账单每行以反引号开头（{@link CsvLine} 会去掉），金额单位是**元**（不是分）。
 * 列名行含「商户订单号」，按表头名取列。
 */
@Component
public class WechatBillParser extends HeaderMappedBillParser {

    @Override
    public PayChannel channel() {
        return PayChannel.WECHAT_NATIVE;
    }

    @Override
    protected String[] outTradeNoHeaders() {
        return new String[]{"商户订单号"};
    }

    @Override
    protected String[] channelTradeNoHeaders() {
        return new String[]{"微信订单号"};
    }

    @Override
    protected String[] amountHeaders() {
        // 应结订单金额 扣掉代金券，对账要用「订单金额」；两个都认，优先前者
        return new String[]{"订单金额", "应结订单金额"};
    }

    @Override
    protected String[] statusHeaders() {
        return new String[]{"交易状态"};
    }

    @Override
    protected String[] refundHeaders() {
        return new String[]{"微信退款单号", "退款金额"};
    }
}
