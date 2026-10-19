package com.mall.portal.controller;

import com.mall.common.result.R;
import com.mall.core.model.vo.UserVO;
import com.mall.core.security.UserContext;
import com.mall.core.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "用户")
@RestController
@RequestMapping("/api/v1/portal/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "当前登录用户信息")
    @GetMapping("/me")
    public R<UserVO> me() {
        return R.ok(userService.getById(UserContext.requireUserId()));
    }
}
