package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;



    @Override
    public Result queryHotBlog(Integer current) {
                // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    @Override
    public Result queryBlogById(Long id) {

        //1.查询blog
        Blog blog = getById(id);

        if (blog == null){
            return Result.fail("笔记已被删除或不存在!");
        }

        queryBlogUser(blog);
        // 判断是否点赞过
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        // 2.获取当前用户id
        Long userId = user.getId();
        // 3. 判断是否点赞过
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    @Override
    public Result likeBlog(Long id) {

        // 1.获取当前用户id
        Long userId = UserHolder.getUser().getId();

        if(userId == null){
            return Result.fail("请先登录!");
        }

        // 2. 判断是否点赞过
        String key = BLOG_LIKED_KEY +id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if (score == null){
            // 3. 没点过赞
            boolean isSuccess = update().setSql("liked=liked + 1").eq("id",id).update();

            if (isSuccess){
                //按点赞时间排序
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }

        }else {
            boolean isSuccess = update().setSql("liked=liked - 1").eq("id",id).update();

            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }

        }

        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY +id;
        //查询点赞前五的用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);

        if (top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());


        String idStr = StrUtil.join(",", ids);
        // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)

        List<UserDTO> userDTOS = userService.query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list().stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean success = save(blog);

        if (!success){
            return  Result.fail("发布失败");
        }

        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();

        for (Follow follow : follows){

            Long userId = follow.getUserId();

            String key = RedisConstants.FEED_KEY+userId;

            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());

        }

        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {

        //1.获取用户id
        Long userId = UserHolder.getUser().getId();

        //2.查询用户的redis信箱并动态排序
        String key = RedisConstants.FEED_KEY+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);

        if (typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        //3.获取blogId、时间戳、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        //记录offset
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples){
            //获取blogId
            ids.add(Long.valueOf(Objects.requireNonNull(tuple.getValue())));
            //获取时间戳
            long time = Objects.requireNonNull(tuple.getScore()).longValue();
            if (time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }

        //根据id查询blog,ids已是有序的但直接查询sql会将其重新排序
        String idStr = StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Blog blog : blogs){
            //查询blog有关的用户
            queryBlogUser(blog);
            // 判断是否点赞过
            isBlogLiked(blog);
        }

        //封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(os);

        return Result.ok(r);
    }
}
