package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

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
    private FollowMapper followMapper;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {

        Long userId = UserHolder.getUser().getId();

        if (userId ==null){
            return Result.fail("请先登录后操作");
        }

        if (isFollow){
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            save(follow);
        }else {
            remove(new QueryWrapper<Follow>().eq("user_id",userId).eq("follow_user_id",followUserId));
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
}
