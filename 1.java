package com.offcn.seckill.controller;

import com.alibaba.dubbo.config.annotation.Reference;
import com.offcn.entity.Result;
import com.offcn.pay.service.AliPayService;
import com.offcn.pojo.TbPayLog;
import com.offcn.pojo.TbSeckillOrder;
import com.offcn.seckill.service.SeckillOrderService;
import com.offcn.util.IdWorker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/pay")
public class PayController {

    @Reference(timeout =500000 )
    private AliPayService aliPayService;

    @Reference
    private SeckillOrderService seckillOrderService;


    @Autowired
    private IdWorker idWorker;


    @RequestMapping("/createNative")
    public Map createNative(){
        //1、获取当前登录用户编号
        String userId= SecurityContextHolder.getContext().getAuthentication().getName();
        //2、根据用户id，获取对应秒杀订单
        TbSeckillOrder seckillOrder = seckillOrderService.searchOrderFromRedisByUserId(userId);
        if(seckillOrder!=null) {
         long fen=(long)(seckillOrder.getMoney().doubleValue()*100);
           Map map = aliPayService.createNative(seckillOrder.getId()+"",fen+"" );

           return map;
       }else {
           return new HashMap();
       }
    }

    @RequestMapping("/queryPayStatus")
    public Result queryPayStatus(String out_trade_no){
        //1、获取当前登录用户编号
        String userId= SecurityContextHolder.getContext().getAuthentication().getName();
        Map<String, String> map=null;
        Result result=null;
        int x=1;
        while (true) {
            try {
                map = aliPayService.queryPayStatus(out_trade_no);
            } catch (Exception e) {
                e.printStackTrace();
            }

            //判断map是否为空
            if(map==null){
                System.out.println("支付状态查询服务调用失败");
                result=new Result(false,"支付状态查询服务调用失败");
                break;
            }

            //判断返回结果
            if(map.get("trade_status")!=null&&map.get("trade_status").equals("TRADE_SUCCESS")){
                result=new Result(true,"支付成功");
                //调用保存秒杀订单到数据库
                seckillOrderService.saveOrderFromRedisToDb(userId,Long.valueOf(out_trade_no),map.get("trade_no"));
                //orderService.updateOrderStatus(out_trade_no,map.get("trade_no"));
                break;
            }
            if(map.get("trade_status")!=null&&map.get("trade_status").equals("TRADE_CLOSED")){
                result=new Result(false,"交易被关闭");
                break;
            }
            if(map.get("trade_status")!=null&&map.get("trade_status").equals("TRADE_FINISHED")){
                result=new Result(false,"交易结束");
                break;
            }


            //等待3秒
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            x++;

            //判断x值大于指定数值，跳出循环
            if(x>10){
                result=new Result(false,"查询超时");
                //秒杀订单超时未支付，删除秒杀订单，恢复库存
                seckillOrderService.deleteOrderFromRedis(userId,Long.valueOf(out_trade_no));
               //调用支付宝接口，取消预下单

                break;
            }

        }

        return result;
    }
}
