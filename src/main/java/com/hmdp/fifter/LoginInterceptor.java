package com.hmdp.fifter;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor {


    @Override
    public boolean preHandle(HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Object handler) throws Exception {
        HttpSession session = request.getSession();

        Object user = session.getAttribute("user");

        if(user == null){
            response.setStatus(401);
            return false;
        }

        UserHolder.saveUser((UserDTO) user);

        return true;


    }

    @Override
    public void afterCompletion(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
