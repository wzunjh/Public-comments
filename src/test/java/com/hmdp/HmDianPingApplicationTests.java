package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;



    /*
    * 将数据库中店铺坐标导入
    *
    * */
    @Test
    void GEO(){

        //查询所有店铺数据
        List<Shop> list = shopService.list();

        //将list转为hashmap结构并按typeId分类
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        //遍历
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {

            Long typeId = entry.getKey();
            String key = "shop:geo:"+typeId;

            List<Shop> value = entry.getValue();

            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());

            for (Shop shop : value){
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
            }

            stringRedisTemplate.opsForGeo().add(key,locations);

        }


    }

    /*
    *
    * HyperLogLog百万数据UV统计
    *
    * */
    @Test
    void HyperLog(){

        String [] value = new String[1000];

        int j =0;
        for (int i=0; i<100000;i++){
            j = i%1000;

            value[j] = "user_id" + i;
            if (j == 999){
                stringRedisTemplate.opsForHyperLogLog().add("hl",value);
            }

        }

        Long size = stringRedisTemplate.opsForHyperLogLog().size("hl");
        System.out.println("size = "+size);


    }



}
