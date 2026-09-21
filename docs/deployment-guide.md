# 部署状态与预览指南

> 部署时间：2026-09-22
> 服务器：<服务器IP>（CentOS 7.9 / 8核 / 14G / 99G）

## 一、当前状态

| 组件 | 版本/状态 | 位置 |
|------|----------|------|
| Nginx | 1.20.1 运行中 | systemd: `nginx` |
| JDK | Temurin 17.0.20.1 | `/opt/java/jdk-17.0.20.1+1` |
| 后端服务 | **运行中** | systemd: `wuling-server` |
| MySQL | 8.0.46 (healthy) | Docker: `wuling-mysql` |
| Redis | 7 | Docker: `wuling-redis` |
| RabbitMQ | 3 | Docker: `wuling-rabbitmq` |
| 后台前端 | 已部署 | `/opt/wuling/web` |
| 数据库迁移 | V1–V10 完成 | Flyway |

## 二、备案期间的预览入口（当前可用）

> 备案拦截针对 80/443 标准端口，非标端口可绕过。

| 用途 | 地址 |
|------|------|
| **运营后台** | http://<服务器IP>:8088/ |
| **小程序 API** | http://<服务器IP>:8089 |

**后台账号**：`<账号>` / `<密码>`（见密码管理）

### 小程序端接入预览

编辑 `user-h5/config.js`：

```js
module.exports = {
  USE_MOCK: false,                              // 切到真实接口
  BASE_URL: 'http://<服务器IP>:8089',        // 预览地址
  STORE_TYPE_CACHE_TTL: 60 * 60 * 1000
};
```

> ⚠️ 微信开发者工具需勾选「不校验合法域名」（详情 → 本地设置）。

## 三、备案通过后的切换步骤

1. 确认备案状态为「已备案」
2. 验证 80 端口可访问：`curl http://wulingshiguang.top/`
3. 申请 HTTPS 证书（Let's Encrypt 或腾讯云免费证书）
4. 更新 Nginx 配置启用 443
5. 小程序 `BASE_URL` 改为 `https://api.wulingshiguang.top`
6. 微信公众平台配置 `request 合法域名`
7. 删除预览配置 `/etc/nginx/conf.d/wuling-preview.conf`（可选）

### 域名规划（已配置，待备案生效）

| 域名 | 用途 | Nginx 配置 |
|------|------|-----------|
| `wulingshiguang.top` | 运营后台 | `/etc/nginx/conf.d/wuling.conf` |
| `www.wulingshiguang.top` | 运营后台 | 同上 |
| `api.wulingshiguang.top` | 小程序 API | 同上（反代 8080） |

## 四、服务管理命令

```bash
# 后端
systemctl status wuling-server
systemctl restart wuling-server
journalctl -u wuling-server -f

# Nginx
systemctl reload nginx
nginx -t

# 中间件
cd /opt/wuling/deploy && docker compose ps
```

## 五、验证记录（10/10 通过）

```
后台首页        HTTP 200  「五零时光运营后台」
主 JS 资源      HTTP 200
健康检查        {"status":"UP"}
/api/v1/app/stores                 门店 5 家
/api/v1/app/menu                   菜单 2 个 tab
/api/v1/app/coupons                优惠券 3 张
/api/v1/app/member-levels          会员等级 3 档
/api/v1/app/config/home            快捷入口 4 个
/api/v1/app/config/cities          城市 3 个
/api/v1/app/stored-value/packages  储值套餐 3 个
/api/v1/app/gift-cards/denominations 礼品卡面额 3 个
/api/v1/app/points/products        积分商品 2 个
/orders 未登录                     8888（鉴权正常）

后台登录        code=0，token 172 字符
商品列表        total=18
CRUD 资源数     43
```

## 六、部署过程中的问题与修复

| # | 问题 | 根因 | 修复 |
|---|------|------|------|
| 1 | 域名访问 502 | 腾讯云安全组未放行 80/443 | 控制台放行 |
| 2 | 服务启动失败 | `application-prod.yml` 里写 `spring.profiles.active` 非法（profile-specific 文件禁止定义该属性） | 改为启动参数 `--spring.profiles.active=prod` |
| 3 | 配置占位符失效 | 写入时 `${}` 带了转义反斜杠 | 重写配置文件 |
| 4 | 前端构建失败 | Windows 下 `components.d.ts` 被占用（errno -4094） | 生产构建禁用 dts 生成 |

## 七、待办

| 项 | 依赖 | 说明 |
|----|------|------|
| HTTPS 证书 | 备案通过 | 需 80 端口可验证 |
| 小程序合法域名 | 备案通过 | 微信公众平台配置 |
| 腾讯云短信 | 申请签名模板 | 当前 `SMS_ENABLED=false`，验证码打日志 |
| 微信支付 | 商户号 | 当前为 Mock 通道 |
| 数据库备份 | — | 建议加定时任务 |
| 监控告警 | — | 建议接入 |
