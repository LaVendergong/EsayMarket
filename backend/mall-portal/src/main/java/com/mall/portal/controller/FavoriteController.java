package com.mall.portal.controller;

import com.mall.common.result.PageResult;
import com.mall.common.result.R;
import com.mall.core.model.vo.FavoriteVO;
import com.mall.core.security.UserContext;
import com.mall.core.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "收藏")
@RestController
@RequestMapping("/api/v1/portal/favorite")
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    @Operation(summary = "收藏/取消收藏，返回操作后的状态（true=已收藏）")
    @PostMapping("/{productId}")
    public R<Boolean> toggle(@PathVariable Long productId) {
        return R.ok(favoriteService.toggle(UserContext.requireUserId(), productId));
    }

    @Operation(summary = "我的收藏")
    @GetMapping("/page")
    public R<PageResult<FavoriteVO>> page(@RequestParam(defaultValue = "1") long pageNum,
                                          @RequestParam(defaultValue = "10") long pageSize) {
        return R.ok(favoriteService.page(UserContext.requireUserId(), pageNum, pageSize));
    }

    @Operation(summary = "商品详情页判断是否已收藏")
    @GetMapping("/check/{productId}")
    public R<Boolean> check(@PathVariable Long productId) {
        return R.ok(favoriteService.isFavorite(UserContext.requireUserId(), productId));
    }
}
