package com.mall.admin.controller;

import com.mall.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "健康检查")
@RestController
@RequestMapping("/api/admin")
public class PingController {

    @Operation(summary = "ping")
    @GetMapping("/ping")
    public R<String> ping() {
        return R.ok("pong from mall-admin");
    }
}
