package com.mall.portal.controller;

import com.mall.common.result.R;
import com.mall.core.model.dto.CartAddDTO;
import com.mall.core.model.vo.CartVO;
import com.mall.core.security.UserContext;
import com.mall.core.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "购物车")
@RestController
@RequestMapping("/api/portal/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @Operation(summary = "购物车列表（含实时价格/可售库存/是否失效）")
    @GetMapping
    public R<List<CartVO>> list() {
        return R.ok(cartService.list(UserContext.requireUserId()));
    }

    @Operation(summary = "加入购物车（同一 SKU 累加数量）")
    @PostMapping
    public R<Long> add(@Valid @RequestBody CartAddDTO dto) {
        return R.ok(cartService.add(UserContext.requireUserId(), dto));
    }

    @Operation(summary = "修改数量")
    @PutMapping("/{id}")
    public R<Void> updateQuantity(@PathVariable Long id, @RequestParam Integer quantity) {
        cartService.updateQuantity(UserContext.requireUserId(), id, quantity);
        return R.ok();
    }

    @Operation(summary = "选中/取消选中 checked=0|1")
    @PutMapping("/{id}/checked")
    public R<Void> updateChecked(@PathVariable Long id, @RequestParam Integer checked) {
        cartService.updateChecked(UserContext.requireUserId(), id, checked);
        return R.ok();
    }

    @Operation(summary = "删除条目")
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        cartService.remove(UserContext.requireUserId(), id);
        return R.ok();
    }

    @Operation(summary = "清空选中项")
    @DeleteMapping("/checked")
    public R<Void> clearChecked() {
        cartService.clearChecked(UserContext.requireUserId());
        return R.ok();
    }
}
