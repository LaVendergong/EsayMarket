package com.mall.portal.controller;

import com.mall.common.result.R;
import com.mall.core.model.vo.DigitalGrantVO;
import com.mall.core.model.vo.GrantContentVO;
import com.mall.core.security.UserContext;
import com.mall.core.service.DigitalGoodsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "虚拟商品凭证")
@RestController
@RequestMapping("/api/v1/portal/digital")
@RequiredArgsConstructor
public class DigitalController {

    private final DigitalGoodsService digitalGoodsService;

    @Operation(summary = "我的卡券（虚拟商品凭证，不含明文）")
    @GetMapping("/mine")
    public R<List<DigitalGrantVO>> mine() {
        return R.ok(digitalGoodsService.mine(UserContext.requireUserId()));
    }

    @Operation(summary = "某订单的凭证（下单后跳转用）")
    @GetMapping("/order/{orderId}")
    public R<List<DigitalGrantVO>> byOrder(@PathVariable Long orderId) {
        return R.ok(digitalGoodsService.byOrder(UserContext.requireUserId(), orderId));
    }

    @Operation(summary = "查看明文（卡密/下载链接）；每次调用都会记一次查看，售后据此判断")
    @PostMapping("/{id}/reveal")
    public R<GrantContentVO> reveal(@PathVariable Long id) {
        return R.ok(digitalGoodsService.reveal(UserContext.requireUserId(), id));
    }
}
