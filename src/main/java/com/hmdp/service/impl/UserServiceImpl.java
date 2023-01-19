package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SmsUtils;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }

        String code = RandomUtil.randomNumbers(6);

        //将code存入redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        System.out.println(code);

        SmsUtils.sendSms(phone,code);   //发送验证码

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }

       String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);


       if (cacheCode == null || ! cacheCode.equals(code)){
           return Result.fail("验证码错误");
       }

       User user = query().eq("phone",phone).one();

       if(user == null){
           user = creatUserWithPhone(phone);   //不存在就注册一个
       }

       //生成随机token
       String token = UUID.randomUUID().toString(true);
       UserDTO userDTO =BeanUtil.copyProperties(user,UserDTO.class);      //类型转化
       Map<String,Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true).setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));

       String tokenKey = LOGIN_USER_KEY+token;
       stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);

       stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);

       return Result.ok(token);
    }

    @Override
    public Result sign() {

        //1.获取当前用户Id
        Long userId = UserHolder.getUser().getId();

        //2.获取日期
        LocalDateTime dateTime = LocalDateTime.now();

        //拼接key
        String format = dateTime.format(DateTimeFormatter.ofPattern(":yyyyMM"));

        String key = USER_SIGN_KEY+userId+format;

        int dayOfMonth = dateTime.getDayOfMonth();

        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1, true);

        return Result.ok();
    }

    @Override
    public Result jntm() {

        //1.获取当前用户Id
        Long userId = UserHolder.getUser().getId();

        //2.获取日期
        LocalDateTime dateTime = LocalDateTime.now();

        //拼接key
        String format = dateTime.format(DateTimeFormatter.ofPattern(":yyyyMM"));

        String key = USER_SIGN_KEY+userId+format;

        int dayOfMonth = dateTime.getDayOfMonth();

        List<Long> list = stringRedisTemplate.opsForValue().bitField(
                key,
                //子命令行，get u dayOfMonth offset 从第0位开始的共dayOfMonth长度的bit
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0));

        if (list == null || list.isEmpty()){
            return Result.ok(0);
        }

        //返回的是一个十进制的数
        Long num = list.get(0);
        if (num == 0){
            return Result.ok(0);
        }

        int count = 0; //计数器

        while (true){

            if ((num & 1) ==0){
                break;
            }else{
                count ++;
            }
                num >>>= 1;
        }

        return Result.ok(count);
    }

    private User creatUserWithPhone(String phone) {

        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));

        save(user);

        return user;
    }
}
