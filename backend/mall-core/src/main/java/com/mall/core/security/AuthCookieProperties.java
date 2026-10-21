package com.mall.core.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录态 Cookie 的配置。
 *
 * <p>为什么从 localStorage 迁到 Cookie：localStorage 里的 token **任何 XSS 都能读走**，
 * 而且读走之后可以离线用满整个有效期。改成 {@code HttpOnly} Cookie 后 JS 根本拿不到它，
 * XSS 想"偷 token"这条路就断了（XSS 仍然能在页面里替用户操作，但没法把凭证带出去长期复用）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mall.security.cookie")
public class AuthCookieProperties {

    /** 门户（H5）用的 Cookie 名。两个应用用不同的名字：localhost 下 5173/5174 的 Cookie 是共享的（Cookie 不区分端口） */
    private String portalName = "mall_portal_token";

    /** 管理后台用的 Cookie 名 */
    private String adminName = "mall_admin_token";

    /** 是否只在 HTTPS 下发送。dev 是 http，必须为 false；**prod 必须为 true**（启动自检会拦） */
    private boolean secure = false;

    /** SameSite：Lax 已经能挡掉绝大多数跨站 POST，配合下面的 CSRF 双提交形成两层 */
    private String sameSite = "Lax";

    /** 与 JWT 有效期保持一致（秒），默认 7 天 */
    private long maxAgeSeconds = 604800;
}
