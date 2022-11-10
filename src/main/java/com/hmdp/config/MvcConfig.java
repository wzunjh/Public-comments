package com.hmdp.config;

import com.hmdp.fifter.LoginInterceptor;
import com.hmdp.fifter.RefreshInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
//放行路径
                  "/shop/**",
                  "/voucher/**",
                  "/shop-type/**",
                  "/upload/**",
                  "/blog/hot",
                  "/user/code",
                  "/user/login"

                ).order(1);

        //先拦截所有（先执行）
        registry.addInterceptor(new RefreshInterceptor(stringRedisTemplate)).addPathPatterns("/**").order(0);
    }
}
