# 舌诊第三方「饮食」原始数据诊断

用于判断某个舌诊报告的饮食模块是「第三方没返回饮食方式」还是「我们解析时丢了」。

## 1. 准备信息

### 第三方 reportId

App 接口不暴露第三方 `report_id`，从数据库读取：

```sql
SELECT id, report_id, report_status, physique, create_time
FROM tongue_report
WHERE id = 2 AND deleted = 0;
```

### App 登录 token

在微信开发者工具 Console 执行：

```js
wx.getStorageSync('milkTea:auth:token');
```

### 第三方 appSecret

只存在于部署环境变量 `TONGUE_APP_SECRET`，不要写进代码、日志或聊天记录。

## 2. 在线调用第三方并对比 App 解析结果

PowerShell：

```powershell
$env:TONGUE_APP_SECRET = '<生产环境 TONGUE_APP_SECRET>'
node scripts/tongue-report-inspect.mjs `
  --report-id '<数据库 report_id>' `
  --app-report-id 2 `
  --token '<小程序 token>'
```

脚本会：

1. 按生产 `TongueSigner.signGet` 规则生成 `appKey/appSecret/timestamp/nonce/contentMd5/sign`；
2. 调 `GET {TONGUE_BASE_URL}/api/tongueimage/getReport?reportId=...`；
3. 把第三方原始 `data` 保存到 `.tmp/tongue-inspect/<reportId>-raw.json`；
4. 打印饮食节点原始树；
5. 调 `GET /api/v1/app/tongue/reports/{appReportId}` 拿 App 解析结果；
6. 输出判定。

## 3. 离线分析数据库 report_json

如果拿不到第三方 secret，可以把数据库 `tongue_report.report_json` 导出成文件，再离线分析：

```powershell
node scripts/tongue-report-inspect.mjs `
  --raw-file .tmp/tongue-inspect/report_json.json `
  --parsed-file .tmp/tongue-inspect/app-response.json
```

`--parsed-file` 是 App 接口 `GET /api/v1/app/tongue/reports/{id}` 的完整响应或 `data` 节点。

## 4. 输出与判定

饮食直接子节点会逐条输出：

| kind                | 含义                                               |
| ------------------- | -------------------------------------------------- |
| `products`          | 推荐商品节点，会被解析成 `products`                |
| `parsed-section`    | 已出现在 App 的 `sections`                         |
| `third-party-empty` | 原始节点只有标题/没有内容，当前隐藏符合预期        |
| `parser-dropped`    | 原始有内容但 App `sections` 缺失，疑似我们解析丢了 |
| `uncompared`        | 只拿到原始数据，未提供 App 解析结果                |

最终判定：

- `第三方未返回饮食方式`：原始饮食节点下没有 `饮食方式` 子节点；
- `第三方返回了饮食方式，但节点为空`：有节点但没有正文/描述/媒体/子节点；
- `疑似被我们解析丢弃`：原始有内容但 App `sections` 没有，需要继续查 `TongueReportParser.flattenSections/block`；
- 退出码：`0` 正常，`2` 疑似解析丢弃，`1` 配置/网络/JSON 错误。

## 5. 安全说明

- `TONGUE_APP_SECRET` 只从环境变量读取，脚本只打印不含 secret 的签名摘要；
- 原始 JSON 只写到 `.tmp`，该目录已在 `.gitignore` 中；
- 脚本只读，不会修改数据库、不会调用检测上传、不会改生产接口；
- 原始报文可能包含第三方媒体 URL，不要提交或外发。
