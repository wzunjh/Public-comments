package com.hmdp.config;

import com.hmdp.fifter.LoginInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

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

                );
    }
}
