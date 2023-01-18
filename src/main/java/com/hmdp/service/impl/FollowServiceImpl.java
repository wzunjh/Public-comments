package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {

        Long userId = UserHolder.getUser().getId();

        String key = RedisConstants.FOLLOWS_ID+userId;

        if (userId ==null){
            return Result.fail("请先登录后操作");
        }

        if (isFollow){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean success = save(follow);
            if (success){
                stringRedisTemplate.opsForSet().add(key, String.valueOf(followUserId));
            }
        }else {
            boolean success = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));

            if (success){
                stringRedisTemplate.opsForSet().remove(key,String.valueOf(followUserId));
            }
        }

        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {

        Long userId = UserHolder.getUser().getId();

        if (userId ==null){
            return Result.fail("请先登录后操作");
        }

        int count = Math.toIntExact(query().eq("user_id", userId).eq("follow_user_id", followUserId).count());

        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {

        Long userId = UserHolder.getUser().getId();

        String key = RedisConstants.FOLLOWS_ID+userId;

        String key2 = RedisConstants.FOLLOWS_ID+id;

        //交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key,key2);

        if (intersect == null || intersect.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());

        System.out.println(ids);

        List<UserDTO> users = userService.listByIds(ids)
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(users);
    }
}
