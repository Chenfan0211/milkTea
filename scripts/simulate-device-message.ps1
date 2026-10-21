<#
.SYNOPSIS
  设备消息本地模拟投递（茶机队列对接联调脚本）。

.DESCRIPTION
  在没有 MNS 密钥的情况下，把「设备状态 / 胶囊冲泡记录」样本消息直接投递到
  RabbitMQ 的业务交换机，绕过 MNS 拉取环节，验证后续全链路：

     投递 -> DeviceMessageConsumer -> DeviceService 落库 -> 后台设备管理/冲泡记录页可见

  这样能在拿到密钥之前就确认「解析后的消息结构、幂等、门店快照、页面字段」都是对的。

  实现方式：走 RabbitMQ 管理插件的 HTTP publish 接口
  （POST /api/exchanges/{vhost}/{exchange}/publish），无需额外安装客户端库。
  已按 docs/mq-infrastructure.md 的「本地经 SSH 隧道访问 MQ」约定默认指向
  localhost:15672（隧道映射到服务器 5672 时，请改用 -ManagePort 对应的管理端口）。

.EXAMPLE
  # 投 1 条状态消息 + 1 条冲泡记录（默认设备编码取自对方文档示例）
  powershell -File scripts/simulate-device-message.ps1 -Password <RABBITMQ_PASSWORD>

.EXAMPLE
  # 批量投 5 条冲泡记录（用于验证唯一键去重：重复投同一批不会产生重复记录）
  powershell -File scripts/simulate-device-message.ps1 -Type upload -Count 5 -Password <pwd>

.EXAMPLE
  # 演示数据：指定上报时间与序列号起点（每条单独调用，即可把时间铺到不同日期）
  powershell -File scripts/simulate-device-message.ps1 -Type upload -BaseTime '2026-10-16 09:12:00' -SerialStart 101 -Password <pwd>
  # 幂等验证：同样的 BaseTime + SerialStart 再投一次，冲泡记录条数不应增加

.EXAMPLE
  # 只打印将要投递的报文，不真正发送
  powershell -File scripts/simulate-device-message.ps1 -DryRun
#>
param(
    [ValidateSet('status', 'upload', 'both')]
    [string]$Type = 'both',

    [string]$DeviceSn = 'ZPDS2512090086G',
    [string]$DeviceName = '869978072404913',

    # 上报时间（yyyy-MM-dd HH:mm:ss）。留空则用当前时间；重复投递请固定该值以验证去重
    [string]$BaseTime = '',

    [int]$Count = 1,

    # 冲泡记录序列号起始值：第 i 条报文的 serialNumber = SerialStart + i - 1。
    # 为什么需要它：演示数据要看起来像真实流水（序列号连续），
    # 且验证幂等时可用「同一 BaseTime + 同一 SerialStart」原样重投。
    [int]$SerialStart = 1,

    [string]$HostName = 'localhost',
    [int]$ManagePort = 15672,
    [string]$User = 'wuling',
    [string]$Password = $env:RABBITMQ_PASSWORD,
    [string]$Vhost = '/',
    [string]$Exchange = 'wuling.business.exchange',
    [string]$RoutingKey = 'wuling.device.message',

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

function New-Envelope {
    param([string]$Kind, [string]$ReportTime, [int]$Index, [int]$Serial)

    $sourceId = "sim-$Kind-{0}-{1}" -f ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()), $Index
    if ($Kind -eq 'status') {
        # 交替投递 ONLINE / OFFLINE：用于验证「状态未变化不写流水、变化才写」
        $status = if ($Index % 2 -eq 0) { 'ONLINE' } else { 'OFFLINE' }
        return [ordered]@{
            type            = 'status'
            deviceSn        = $DeviceSn
            deviceName      = $DeviceName
            onlineStatus    = $status
            reportTime      = $ReportTime
            rawPayload      = (@{ deviceSN = $DeviceSn; deviceName = $DeviceName; status = $status; baseCreateTime = $ReportTime } | ConvertTo-Json -Compress)
            sourceMessageId = $sourceId
        }
    }
    return [ordered]@{
        type            = 'upload'
        deviceSn        = $DeviceSn
        deviceName      = $DeviceName
        teaCode         = '209'
        teaName         = '欧米伽-3植物乳'
        customerCode    = '90000001'
        madeDay         = '20251021'
        serialNumber    = "$Serial"
        reportTime      = $ReportTime
        rawPayload      = (@{ deviceSN = $DeviceSn; teaCode = 209; serialNumber = $Serial; baseCreateTime = $ReportTime } | ConvertTo-Json -Compress)
        sourceMessageId = $sourceId
    }
}

function Send-Message {
    param([string]$MessageId, [string]$Body)

    $payload = @{
        properties       = @{
            content_type = 'application/json'
            message_id   = $MessageId
            delivery_mode = 2
            headers      = @{
                'x-message-id' = $MessageId
                'biz-key'      = $DeviceSn
            }
        }
        routing_key      = $RoutingKey
        payload          = $Body
        payload_encoding = 'string'
    } | ConvertTo-Json -Depth 6 -Compress

    if ($DryRun) {
        Write-Host "[dry-run] $MessageId"
        Write-Host $Body
        Write-Host ''
        return
    }

    if ([string]::IsNullOrWhiteSpace($Password)) {
        throw '缺少 RabbitMQ 口令：请用 -Password 传入，或先设置环境变量 RABBITMQ_PASSWORD。'
    }

    $uri = "http://{0}:{1}/api/exchanges/{2}/{3}/publish" -f $HostName, $ManagePort, [uri]::EscapeDataString($Vhost), [uri]::EscapeDataString($Exchange)
    $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${User}:${Password}"))
    $headers = @{ Authorization = "Basic $basic"; 'Content-Type' = 'application/json' }

    $result = Invoke-RestMethod -Method Post -Uri $uri -Headers $headers -Body $payload
    Write-Host ("已投递 {0} routed={1}" -f $MessageId, $result.routed)
}

if ([string]::IsNullOrWhiteSpace($BaseTime)) {
    $BaseTime = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
}

Write-Host ("目标：{0}:{1} 交换机={2} 路由键={3}" -f $HostName, $ManagePort, $Exchange, $RoutingKey)
Write-Host ("设备：{0} / {1} 上报时间：{2}" -f $DeviceSn, $DeviceName, $BaseTime)
Write-Host ''

$index = 0
for ($i = 1; $i -le $Count; $i++) {
    $reportTime = $BaseTime
    if ($Count -gt 1) {
        # 批量投递时按秒递增，避免同一条冲泡记录被唯一键去重掉（除非就是要验证去重）
        $reportTime = ([datetime]::ParseExact($BaseTime, 'yyyy-MM-dd HH:mm:ss', $null)).AddSeconds($index).ToString('yyyy-MM-dd HH:mm:ss')
    }
    $index++

    if ($Type -eq 'status' -or $Type -eq 'both') {
        $envelope = New-Envelope -Kind 'status' -ReportTime $reportTime -Index $index -Serial ($SerialStart + $i - 1)
        Send-Message -MessageId $envelope.sourceMessageId -Body ($envelope | ConvertTo-Json -Compress)
    }
    if ($Type -eq 'upload' -or $Type -eq 'both') {
        $envelope = New-Envelope -Kind 'upload' -ReportTime $reportTime -Index $index -Serial ($SerialStart + $i - 1)
        Send-Message -MessageId $envelope.sourceMessageId -Body ($envelope | ConvertTo-Json -Compress)
    }
}

Write-Host ''
Write-Host '完成。核验建议：'
Write-Host '  1) 后端日志出现「MQ 消费成功」与「设备自动建档」；'
Write-Host '  2) 后台「主体管理 -> 设备管理」出现该设备（未绑定门店），「冲泡记录」出现记录；'
Write-Host '  3) 若要验证幂等：用 -DryRun 取到报文后原样重投同一 messageId/唯一键，记录数不应增加。'
