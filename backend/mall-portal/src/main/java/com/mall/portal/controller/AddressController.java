package com.mall.portal.controller;

import com.mall.common.result.R;
import com.mall.core.model.dto.AddressDTO;
import com.mall.core.model.vo.AddressVO;
import com.mall.core.security.UserContext;
import com.mall.core.service.AddressService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "收货地址")
@RestController
@RequestMapping("/api/portal/address")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @Operation(summary = "地址列表")
    @GetMapping
    public R<List<AddressVO>> list() {
        return R.ok(addressService.listByUser(UserContext.requireUserId()));
    }

    @Operation(summary = "新增地址")
    @PostMapping
    public R<Long> add(@Valid @RequestBody AddressDTO dto) {
        return R.ok(addressService.add(UserContext.requireUserId(), dto));
    }

    @Operation(summary = "修改地址")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody AddressDTO dto) {
        addressService.update(UserContext.requireUserId(), id, dto);
        return R.ok();
    }

    @Operation(summary = "删除地址")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        addressService.delete(UserContext.requireUserId(), id);
        return R.ok();
    }

    @Operation(summary = "设为默认地址")
    @PutMapping("/{id}/default")
    public R<Void> setDefault(@PathVariable Long id) {
        addressService.setDefault(UserContext.requireUserId(), id);
        return R.ok();
    }
}
