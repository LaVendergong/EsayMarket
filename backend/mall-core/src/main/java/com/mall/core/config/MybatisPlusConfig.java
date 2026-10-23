package com.mall.core.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.mall.core.tenant.MallTenantHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.mall.core.mapper")
public class MybatisPlusConfig {

    /**
     * 多租户总开关，默认关闭。
     *
     * <p>为什么不是默认开：租户隔离一旦生效，**每一条 SQL 都会被改写**，
     * 而券/评价/对账这类数据的「跨店归属」还没有定论（平台券商户看不看得见？
     * 用户领的券属于哪个店？），一刀切开会让这些功能互相看不见。
     *
     * <p>表结构（tenant_id + 唯一键）和上下文都已就绪，归属规则定了之后：
     * 先按表逐个打开（把 {@link MallTenantHandler} 的忽略清单里的表删掉），
     * 每开一张跑一遍对应验证脚本，最后再把这里默认值改成 true。
     */
    @Value("${mall.tenant.enabled:false}")
    private boolean tenantEnabled;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        if (tenantEnabled) {
            // 多租户插件必须排在分页之前：分页会先算 count，租户条件必须在 count 里也生效，
            // 否则「总数」会是全平台的、而列表只有本店的。
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new MallTenantHandler()));
        }
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(500L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
