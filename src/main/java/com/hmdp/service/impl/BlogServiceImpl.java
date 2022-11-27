package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;


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
        // 1.获取当前用户id
        Long userId = UserHolder.getUser().getId();
        // 2. 判断是否点赞过
        String key = BLOG_LIKED_KEY + blog.getId();
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        blog.setIsLike(BooleanUtil.isTrue(isMember));
    }

    @Override
    public Result likeBlog(Long id) {

        // 1.获取当前用户id
        Long userId = UserHolder.getUser().getId();
        // 2. 判断是否点赞过
        String key = BLOG_LIKED_KEY +id;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());

        if (BooleanUtil.isFalse(isMember)){
            // 3. 没点过赞
            boolean isSuccess = update().setSql("liked=liked + 1").eq("id",id).update();

            if (isSuccess){
                stringRedisTemplate.opsForSet().add(key,userId.toString());
            }

        }else {
            boolean isSuccess = update().setSql("liked=liked - 1").eq("id",id).update();

            if (isSuccess){
                stringRedisTemplate.opsForSet().remove(key,userId.toString());
            }

        }

        return Result.ok();
    }
}
