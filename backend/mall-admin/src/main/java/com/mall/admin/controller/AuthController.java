package com.mall.admin.controller;

import com.mall.common.exception.BusinessException;
import com.mall.common.result.R;
import com.mall.common.result.ResultCode;
import com.mall.core.model.dto.LoginDTO;
import com.mall.core.model.vo.LoginVO;
import com.mall.core.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "后台认证")
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @Operation(summary = "后台登录（仅管理员）")
    @PostMapping("/login")
    public R<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        LoginVO vo = userService.login(dto);
        if (vo.getRole() == null || vo.getRole() != 1) {
            throw new BusinessException(ResultCode.FORBIDDEN, "非管理员账号，无法登录后台");
        }
        return R.ok(vo);
    }
}
