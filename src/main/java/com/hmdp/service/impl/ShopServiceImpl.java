package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {

        Shop shop = queryWithMutex(id);
        if (shop == null){
            return Result.fail("店铺不存在!");
        }

        return Result.ok(shop);
    }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);    //添加分布式锁
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(key);   //释放锁
    }

    //线程池
    private static  final ExecutorService CACHE_REDIS = Executors.newFixedThreadPool(10);


    public Shop queryWithMutex(Long id) {

        String key = CACHE_SHOP_KEY+id;

        //查缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        if (StrUtil.isBlank(shopJson)){
            //未命中直接返回空
            return null;
        }

        RedisData redisData = JSONUtil.toBean(shopJson,RedisData.class);
        Shop shop =JSONUtil.toBean((JSONObject) redisData.getData(),Shop.class);

        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }

        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey); //获取锁

        if (isLock){
            //重建缓存
            CACHE_REDIS.submit(()->{
               this.saveShopRedis(id);
               //释放锁
                unLock(lockKey);
            });
        }

        return shop;
    }

    private void saveShopRedis(Long id){

        //逻辑过期

        Shop shop = getById(id);

        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusMinutes(30));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));

    }


//    public Shop queryWithPassThrough(Long id){
//
//        //防止缓存穿透函数
//
//        String key = CACHE_SHOP_KEY+id;
//        //查缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        if (StrUtil.isNotBlank(shopJson)){
//            return JSONUtil.toBean(shopJson,Shop.class);
//        }
//        //判断命中是否为空值（不是空）
//        if (shopJson != null){
//            return null;
//        }
//
//        Shop shop = getById(id);
//
//        if (shop == null){
//            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);      //存入空值防止缓存穿透
//            return null;
//        }
//
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//
//
//        return shop;
//
//    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();

        if (id == null){
            return Result.fail("店铺id不能为空!");
        }


        updateById(shop);

        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);

        return Result.ok();
    }
}
