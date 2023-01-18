package com.hmdp.fifter;

import com.hmdp.utils.UserHolder;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author 27877
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Object handler) {

        if (UserHolder.getUser() == null){
            response.setStatus(401);
            return false;
        }
        return true;

    }
}
