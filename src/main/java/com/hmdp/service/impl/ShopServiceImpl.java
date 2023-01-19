package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
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

    @Override
    public Result queryByType(Integer typeId, Integer current, Double x, Double y) {

        //判断是否为距离查询
        if(x == null || y==null){

            Page<Shop> shopPage = query().eq("type_id",typeId).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
            return Result.ok(shopPage.getRecords());
        }

        //计算分页大小
        int from = (current - 1) * SystemConstants.MAX_PAGE_SIZE;
        int end  =  current * SystemConstants.MAX_PAGE_SIZE;

        //查询附近商家
        String key = SHOP_GEO_KEY+typeId;

        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(key,
                GeoReference.fromCoordinate(x, y),   //用户经纬度
                new Distance(5000),        //范围半径为5000米
                RedisGeoCommands
                        .GeoRadiusCommandArgs.newGeoRadiusArgs()
                        .includeDistance().limit(end));//返回距离数据并设置查询数据条数

        //解析出shopId
        if (results == null){
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();

        //截取from部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());

        if (list.size() <= from){
            return Result.ok(Collections.emptyList());
        }

        list.stream().skip(from).forEach(result ->{

            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });

        String idStr = StrUtil.join(",",ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (Shop shop : shops){
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        return Result.ok(shops);
    }
}
