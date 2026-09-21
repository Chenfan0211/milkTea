# 消息队列基础设施（RabbitMQ）

> 用途：支付回调、订单超时、分账结算、通知推送的异步解耦
> 方案：死信 + TTL 实现延迟消息（无需插件）

## 一、基础设施组成

```
┌─ RabbitConfig ─────────────────────────────────┐
│  声明式配置：交换机 / 队列 / 绑定 / 死信 / TTL    │
├─ MqProducer ───────────────────────────────────┤
│  消息发送封装：自动 messageId + 持久化 + 日志      │
├─ AbstractMqConsumer ───────────────────────────┤
│  消费基类：幂等 / 重试 / 死信 / ACK  统一处理      │
├─ MqIdempotent ─────────────────────────────────┤
│  基于 Redis SETNX 的消息去重                      │
└─ MqConstants ──────────────────────────────────┘
   命名规范：wuling.{业务}.{类型}
```

## 二、拓扑结构

| 交换机 | 类型 | 用途 |
|--------|------|------|
| `wuling.business.exchange` | direct | 业务消息主交换机 |
| `wuling.delay.exchange` | direct | 延迟消息入口 |
| `wuling.dlx.exchange` | direct | 死信交换机 |

| 队列 | 用途 | 死信 |
|------|------|------|
| `wuling.order.timeout.queue.delay` | 延迟等待（TTL 15min） | → business.exchange |
| `wuling.order.timeout.queue` | 订单超时处理 | → dlq |
| `wuling.order.timeout.queue.dlq` | 订单超时死信 | — |
| `wuling.payment.success.queue` | 支付成功处理 | → dlq |
| `wuling.payment.success.queue.dlq` | 支付成功死信 | — |
| `wuling.settlement.notify.queue` | 结算通知 | → dlq |
| `wuling.settlement.notify.queue.dlq` | 结算通知死信 | — |

## 三、延迟消息实现（死信 + TTL）

```
下单
  ↓ MqProducer.sendDelay()
wuling.delay.exchange
  ↓ routingKey
wuling.order.timeout.queue.delay  ← TTL 15min 排队
  ↓ TTL 到期，经 x-dead-letter-exchange 转投
wuling.business.exchange → wuling.order.timeout.queue
  ↓ 消费者
OrderTimeoutConsumer → OrderService.closeIfUnpaid()
```

**关键点**：延迟队列本身**没有消费者**（消息只在此等待），到期后自动转投业务队列。

## 四、可靠性保障

| 机制 | 实现 |
|------|------|
| **消息不丢** | `deliveryMode=PERSISTENT` + 队列 durable |
| **幂等消费** | Redis SETNX 占位（`wuling:biz:consumed:{messageId}`，TTL 24h） |
| **失败重试** | 消费异常 → `basicNack(requeue=true)`，最多 3 次 |
| **死信兜底** | 超限 → `basicNack(requeue=false)` → DLX → DLQ |
| **手动 ACK** | 业务成功才 ack，避免消息丢失 |
| **并发控制** | 2 并发 + prefetch 10 |
| **幂等失败释放** | 业务失败时释放占位，否则会阻止重试 |

## 五、业务方接入方式

### 发送消息
```java
// 普通消息
mqProducer.send(MqConstants.PAYMENT_SUCCESS_ROUTING_KEY, payload, orderNo);

// 延迟消息
mqProducer.sendDelay(MqConstants.ORDER_TIMEOUT_ROUTING_KEY, orderNo, orderNo);
```

### 消费消息
```java
@Component
public class XxxConsumer extends AbstractMqConsumer {

    public XxxConsumer(MqIdempotent idempotent) {
        super(idempotent);
    }

    @RabbitListener(queues = MqConstants.XXX_QUEUE)
    public void onMessage(Message message, Channel channel) {
        // 幂等/重试/死信/ACK 由基类处理，这里只写业务
        consume(message, channel, msg -> doBusiness(msg));
    }
}
```

### 新增队列
1. `MqConstants` 加队列名/路由键/DLQ 常量
2. `RabbitConfig` 加 `Queue` + `Binding` + `Dlq` Bean
3. 写 Consumer

## 六、配置项

```yaml
app:
  mq:
    order-timeout-ms: 900000      # 订单超时 15 分钟
    max-retry: 3                  # 最大重试次数
    consumer-concurrency: 2       # 消费并发
    prefetch-count: 10            # 预取数量
```

均支持环境变量覆盖：`MQ_ORDER_TIMEOUT_MS` / `MQ_MAX_RETRY` / `MQ_CONSUMER_CONCURRENCY` / `MQ_PREFETCH_COUNT`

## 七、验证记录

### 拓扑创建（线上）
```
交换机(3)：wuling.business.exchange / wuling.delay.exchange / wuling.dlx.exchange
队列(7)：  3 业务 + 3 死信 + 1 延迟队列
绑定(7)：  全部正确
TTL：      900000ms (15分钟)
```

### 端到端（本地，TTL 调为 8 秒验证）
```
01:05:24  MQ 发送延迟消息   bizKey=WX202609220105235258
01:05:33  订单已超时关闭    orderNo=WX202609220105235258   ← 8 秒后自动触发
01:05:33  MQ 消费成功       messageId=24228eb8-...
```

订单状态确认：
```
WX...235258  CANCELED  UNPAID  超时未支付，系统自动关闭  ✅
```

幂等键写入：`wuling:biz:consumed:24228eb8-...`（Redis db3）✅

## 八、踩坑记录

### 1. `@NotNull` 校验早于方法体执行（已修复）
`CreateOrderRequest.userId` 标了 `@NotNull`，但该字段由服务端从 JWT 注入。
`@Valid` 在 Controller 方法**入口**就校验，`request.setUserId()` 永远晚一步，
导致「鉴权已通过但校验失败」返回 400。

**修复**：去掉 DTO 上该字段的校验（它本就不该由客户端提供）。

### 2. 队列参数无法热更新（重要运维约束）
RabbitMQ 队列的 `x-message-ttl` 等参数在**声明时固定**，
修改配置后重启应用**不会**更新已存在队列的参数。

**解决**：改 TTL 需先删队列再重启：
```bash
docker exec wuling-rabbitmq rabbitmqctl delete_queue wuling.order.timeout.queue.delay
systemctl restart wuling-server
```

### 3. 本地隧道端口映射
本地开发经 SSH 隧道访问 MQ，本地 `15672` 映射到服务器 `5672`（AMQP）。
故 `application-dev.yml` 的 rabbitmq.port 配 `15672`，生产配 `5672`。

## 九、运维命令

```bash
# 查看队列
docker exec wuling-rabbitmq rabbitmqctl list_queues name messages

# 查看死信队列堆积
docker exec wuling-rabbitmq rabbitmqctl list_queues name messages | grep dlq

# 手动重放死信（示例）
# 在管理台 http://127.0.0.1:15672 操作，或使用 shovel 插件

# 管理台（本地经隧道）
# http://localhost:15672  wuling / <RABBITMQ_PASSWORD>
```

## 十、后续扩展

| 场景 | 接入方式 |
|------|---------|
| 微信支付回调 | 回调接口快速 ACK，投递 `payment.success` 队列异步处理 |
| 分账执行 | `payment.success` 消费者触发 |
| 结算通知 | T+1 任务投递 `settlement.notify` 队列 |
| 小程序消息推送 | 新增 `wuling.notice.push` 队列 |

> ⚠️ 死信队列需定期巡检，堆积说明业务异常。建议加监控告警（当前需人工查看）。
