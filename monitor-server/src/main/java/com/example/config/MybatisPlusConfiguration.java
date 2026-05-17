package com.example.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 通用配置。注册分页插件，供告警历史等分页查询使用。
 */
@Configuration
public class MybatisPlusConfiguration {

    /**
     * 注册分页拦截器，使 {@code IService.page(Page, Wrapper)} 与 {@code BaseMapper.selectPage} 生效。
     *
     * @return 已配置好分页内插件的拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
