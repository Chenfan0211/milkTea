package com.wuling.device.mq;

import com.rabbitmq.client.Channel;
import com.wuling.common.mq.AbstractMqConsumer;
import com.wuling.common.mq.MqConstants;
import com.wuling.common.mq.MqIdempotent;
import com.wuling.device.mns.DeviceMessageEnvelope;
import com.wuling.device.service.DeviceService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 设备消息消费者（茶机对接）。
 *
 * <p>消息由 {@code MnsPoller} 从阿里云 MNS 转投到 {@code wuling.device.message.queue}，
 * 这里按 messagetype 分派：
 * <ul>
 *   <li>{@code status}：更新设备在线状态，状态变化时写流水；</li>
 *   <li>{@code upload}：写入胶囊冲泡记录（门店取当前绑定作为快照）。</li>
 * </ul>
 *
 * <p>幂等/重试/死信/ACK 全部由 {@link AbstractMqConsumer} 统一处理，
 * 业务失败会自动延迟重试，超限进 DLQ（wuling.device.message.queue.dlq）。
 */
@Component
public class DeviceMessageConsumer extends AbstractMqConsumer {

    private final DeviceService deviceService;

    public DeviceMessageConsumer(MqIdempotent idempotent, DeviceService deviceService) {
        super(idempotent);
        this.deviceService = deviceService;
    }

    @RabbitListener(queues = MqConstants.DEVICE_MESSAGE_QUEUE)
    public void onDeviceMessage(Message message, Channel channel) {
        consume(message, channel, msg -> {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            DeviceMessageEnvelope envelope = objectMapper.readValue(body, DeviceMessageEnvelope.class);
            if (envelope.getDeviceSn() == null || envelope.getDeviceSn().isBlank()) {
                throw new IllegalArgumentException("设备消息缺少 deviceSn，无法归属设备");
            }
            if (DeviceMessageEnvelope.TYPE_STATUS.equals(envelope.getType())) {
                deviceService.handleStatusMessage(envelope);
            } else if (DeviceMessageEnvelope.TYPE_UPLOAD.equals(envelope.getType())) {
                deviceService.handleBrewMessage(envelope);
            } else {
                throw new IllegalArgumentException("未知的设备消息类型：" + envelope.getType());
            }
        });
    }
}
