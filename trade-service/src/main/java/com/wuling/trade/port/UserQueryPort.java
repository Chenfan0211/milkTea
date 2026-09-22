package com.wuling.trade.port;

/**
 * 用户信息查询端口（第 14 期支付接入新增）。
 *
 * <p><b>为什么要走端口而不是直接读 app_user 表</b>：
 * 用户域已于第 11 期拆为 user-service，交易服务直连它的库会形成
 * 「跨服务共享数据库」，后续任何一次用户表结构调整都会同时波及两个服务。
 * 走端口后，未来换成 Feign / gRPC 只需替换实现类。
 *
 * <p><b>失败语义</b>：查询失败返回 null，由调用方按业务规则处理
 * （支付场景应直接报错，不能拿 null 继续下单）。
 */
public interface UserQueryPort {

    /**
     * 查询用户 openid。
     *
     * <p>微信支付 JSAPI 下单必填；<b>必须由服务端依据登录态查询</b>，
     * 绝不接受前端传入，否则可替他人发起支付。
     *
     * @param userId 用户 ID（取自 JWT）
     * @return openid；用户不存在或无 openid 时返回 null
     */
    String findOpenid(Long userId);
}
