package com.mall.core.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档元信息，mall-admin / mall-portal 共用（两个应用都会扫到 com.mall）。
 * 访问：http://localhost:&lt;port&gt;/doc.html
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI mallOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Mall 商城 API")
                        .version("1.0.0")
                        .description("自研电商 MVP：认证 / 用户 / 收货地址 / 分类 / 商品 / 购物车 / 订单。\n\n"
                                + "约定（详见 docs/AI-HANDOFF.md §6.1）：统一返回 R<T>；业务失败时 HTTP 仍是 200，"
                                + "错误码在 body.code 里；未认证才是真正的 HTTP 401（body 为空）。\n\n"
                                + "鉴权：先调对应端的 /auth/login 拿 token，再在右上角 Authorize 里填 "
                                + "Bearer &lt;token&gt;。"))
                .components(new Components().addSecuritySchemes("bearer-jwt",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}
