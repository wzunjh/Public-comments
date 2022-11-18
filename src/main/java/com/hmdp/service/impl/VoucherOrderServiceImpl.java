package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;


    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {

        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始!");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已结束!");
        }

        if (voucher.getStock() < 1){
            return Result.fail("优惠券已抢完!");
        }

        //一人一单
        Long userId = UserHolder.getUser().getId();


        //悲观锁
        synchronized (userId.toString().intern()){
            int count = Math.toIntExact(query().eq("user_id", userId).eq("voucher_id", voucherId).count());

            if (count > 0){
                return Result.fail("每人限抢购一单!");
            }

            boolean success = seckillVoucherService.update().setSql("stock=stock-1").eq("voucher_id",voucherId).gt("stock",0).update();

            if (!success){
                return Result.fail("抢购失败!");
            }

            VoucherOrder voucherOrder = new VoucherOrder();

            long orderId = redisIdWorker.nextId("order");

            voucherOrder.setId(orderId);

            voucherOrder.setUserId(userId);

            voucherOrder.setVoucherId(voucherId);

            save(voucherOrder);


            return Result.ok(orderId);
        }
    }
}
