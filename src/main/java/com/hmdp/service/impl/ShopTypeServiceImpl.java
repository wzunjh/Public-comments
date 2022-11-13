package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getlist() {

        //1.从redis中查询店铺类型缓存
        String shopType = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOPTYPE_KEY);
        //2.判断是否为空
        if (StrUtil.isNotBlank(shopType)) {
            //3.存在，直接返回
            List<ShopType> shopTypes = JSONUtil.toList(shopType, ShopType.class);
            return Result.ok(shopTypes);
        }
        //4.不存在，从数据库中查询写入redis
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        //5.不存在，返回错误
        if (shopTypes == null) {
            return Result.fail("分类不存在");
        }
        //6.存在，写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOPTYPE_KEY,JSONUtil.toJsonStr(shopTypes));
        //7.返回
        return Result.ok(shopTypes);
    }

}
