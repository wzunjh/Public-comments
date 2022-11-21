package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static com.hmdp.utils.RedisConstants.LOCK_ORDER;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;


    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();   //创建一个单线程

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());     //类初始化后进行线程提交
    }

    private  class VoucherOrderHandler implements Runnable{

        String queueName = "stream.orders";

        @Override
        public void run() {

            while (true){
                try{

                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlePendingList();
                }
            }


        }

        //消息队列获取异常处理方法
        private void handlePendingList() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS s1 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        break;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    handleVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {

        //异步下单
        //一人一单
        Long userId = voucherOrder.getUserId();

        Long voucherId = voucherOrder.getVoucherId();

        //创建锁对象
        RLock lock = redissonClient.getLock(LOCK_ORDER + userId);

        boolean isLock = lock.tryLock();  //获取锁

        if (!isLock) {
            log.error("不允许重复下单!");
            return;
        }

        try {
            int count = Math.toIntExact(query().eq("user_id", userId).eq("voucher_id", voucherId).count());
            if (count > 0){
                log.error("每人限抢购一单!");
                return;
            }

            boolean success = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id",voucherId).gt("stock",0).update();

            if (!success){
                log.error("库存不足,抢购失败!");
                return;
            }
            //创建订单
            save(voucherOrder);
        } finally {
            lock.unlock();
        }
    }


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillVoucher(Long voucherId) {

        Long userId = UserHolder.getUser().getId();
        //下单成功,生成订单ID
        long orderId = redisIdWorker.nextId("order");

        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );

        assert result != null;
        int r = result.intValue();

        // 2.判断结果是否为0,不为0代表没有购买资格
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单!");
        }

        // 4.返回订单id
        return Result.ok(orderId);
        }

}




































//    @Override
//    @Transactional
//    public Result seckillVoucher(Long voucherId) {
//
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始!");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已结束!");
//        }
//
//        if (voucher.getStock() < 1){
//            return Result.fail("优惠券已抢完!");
//        }
//
//        //一人一单
//        Long userId = UserHolder.getUser().getId();
//
//        //创建锁对象
//        RLock lock = redissonClient.getLock(LOCK_ORDER + userId);
//
//        boolean isLock = lock.tryLock();  //获取锁
//
//        if (!isLock){
//            return Result.fail("不允许重复下单!");
//        }
//
//
//        try {
//            int count = Math.toIntExact(query().eq("user_id", userId).eq("voucher_id", voucherId).count());
//
//            if (count > 0){
//                return Result.fail("每人限抢购一单!");
//            }
//
//            boolean success = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id",voucherId).gt("stock",0).update();
//
//            if (!success){
//                return Result.fail("库存不足,抢购失败!");
//            }
//
//            VoucherOrder voucherOrder = new VoucherOrder();
//
//            long orderId = redisIdWorker.nextId("order");
//
//            voucherOrder.setId(orderId);
//
//            voucherOrder.setUserId(userId);
//
//            voucherOrder.setVoucherId(voucherId);
//
//            save(voucherOrder);
//
//            return Result.ok(orderId);
//
//        } finally {
//            lock.unlock();  //释放锁
//        }
//    }

