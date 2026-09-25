# 门店地图接入腾讯位置服务 · 调整计划

> 创建日期：2026-09-23
> 涉及：`user-h5/pages/menu`（改造入口）、`server`（新增小程序端地理接口）、`app_config`（密钥注入）
> 前置结论：腾讯位置服务 key/sk 已在服务端就绪，小程序端从未接入

## 一、背景与问题

用户在点单页门店选择层截图中圈出红色区域（约 790rpx 高的 Banner），
问「这块为什么不显示腾讯地图」。

排查结论（证据链）：

1. 该区域**不是地图组件**，只是一张静态图：`pages/menu/menu.wxml` 129–147 行
   仅含 `<image src="/assets/images/3x/menu-banner.jpg">` + 遮罩 + 「查看门店地图」文案。
   全项目在此处**不存在 `<map>` 组件**。
2. 历史上点单页曾内嵌 `<map>`，在 Skyline 渲染器下白屏，故被替换为「可点击入口」
   （见 `menu.wxml` 130–133 行注释、`menu.js` 278 行注释）。
3. 真正的 `<map>` 只存在于独立页 `pages/store-map/store-map`（`renderer: webview`），
   该页是二级页面，不在截图所示画面内。
4. 该入口点击后若门店数据未就绪，`anchor` 回落为长沙市默认坐标
   `28.2282, 112.9388`（`store-map.js` 14、43–47 行），会出现「地图在、标记空」的观感。

即：**红框不显示腾讯地图是设计如此（静态占位图），不是渲染故障。**

## 二、现状盘点：密钥基础设施已存在

排查中发现项目**已具备**腾讯位置服务服务端代理能力，可直接复用，无需新建：

| 组件 | 位置 | 现状 |
|------|------|------|
| 密钥存储 | `app_config.config_key = 'tencent_map_key'`，值 `{ key, sk }` | 迁移 `V11__tencent_map_config.sql` 已建占位，**真实值待注入** |
| 缓存层 | `AppConfigCacheService` | Redis 优先 + 回源 MySQL，TTL 1 个月，`evict()` 主动失效 |
| 代理服务 | `GeoCodeService` | 已实现腾讯 SN 签名（字典序 + 追加 SK + MD5），调用 `/ws/geocoder/v1/` |
| 管理端接口 | `AdminGeoController` | `POST /api/v1/admin/geo/geocode` 地址转经纬度 |
| 敏感键防护 | `AppConfigController#SENSITIVE_KEYS` | 已确保 `tencent_map_key` **不对外下发** |

**缺口**：`GeoCodeService` 只暴露了「地址→经纬度」（geocoder），
且仅管理端可用；小程序端既没有代理接口，也没有消费方。

## 三、目标

1. 点单页门店选择层不再用「假地图 Banner」误导用户。
2. 用户能在**真实腾讯地图**中查看并导航到门店，零白屏风险。
3. 服务端补齐小程序端可用的腾讯 LBS 代理接口（逆地址解析 / 距离矩阵）。
4. **Secret key 永不进入小程序包、永不进入仓库、永不经接口下发。**

## 四、方案

### 第 1 层：地图体验（不依赖 key）

- 删除 `menu.wxml` 中的「假地图」入口区块（129–147 行）。
- 门店列表直接上移为首屏内容；每行门店卡已有导航按钮
  （`store-list-card__action` → `handleNavigate` → `wx.openLocation`），
  点击即拉起**真实腾讯地图**，带门店气泡与路线规划。
- 清理 `menu.wxss` 中随之失效的死样式（317–370 行共 8 个选择器）。
- 删除 `menu.js#openStoreMap` 死方法。

### 第 2 层：服务端 LBS 代理（key 的正确用法）

新增小程序端地理接口，密钥全程留在服务端：

```
小程序 ──→ 你自己的后端 ──→ 腾讯 LBS WebService（带 key + SN 签名）
           /api/v1/app/geo/regeo      逆地址解析（经纬度 → 结构化地址）
           /api/v1/app/geo/distance   距离矩阵（用户位置 → 各门店真实距离）
```

改造点：

1. `GeoCodeService` 扩展：新增 `reverseGeocode(lat, lng)` 与 `distanceMatrix(from, toList)`，
   复用已有 `md5` / `buildQuery` 签名工具，抽出公共 `callTencent(path, params)`。
2. 新增 `AppGeoController`（`/api/v1/app/geo/**`），只接收坐标、只返回结果，**不回传 key**。
3. 小程序 `utils/api.js` 新增 `fetchReverseGeocode()` / `fetchStoreDistances()`；
   门店距离由「前端 Haversine 直线估算」升级为「服务端真实距离」。
4. 距离结果按 TTL 短缓存（如 5 分钟），避免高频打腾讯配额。

### 密钥注入方式（不写进仓库）

`.env` / `.env.prod` / `application.yml` 均**不落真实密钥**。真实值二选一：

**方式 A：部署时执行 SQL**（推荐，与 `V11` 注释一致）

```sql
UPDATE app_config
   SET value = JSON_OBJECT('key', '<真实Key>', 'sk', '<真实SK>')
 WHERE config_key = 'tencent_map_key';
```

随后让缓存失效：调用 `AppConfigCacheService#evict("tencent_map_key")` 或重启服务。

**方式 B：环境变量注入**
`TENCENT_MAP_KEY` / `TENCENT_MAP_SK` 由部署脚本写入数据库（`V11` 注释已预留该路径）。

> 本机执行 SQL 的口令从 `.env.local.properties` 读取（该文件已被 `.gitignore` 忽略）。

## 五、腾讯后台需要确认的配置

腾讯位置服务的 key 按**产品类型**绑定，使用 WebService API 需确认：

- [ ] 该 key 已勾选「**WebServiceAPI**」产品。
- [ ] 已开启 **SN 校验**（否则 `GeoCodeService` 的签名会被忽略或报错）；
      若未开启，需在 `GeoCodeService` 中走「不带 sig」分支（现有代码已兼容 `sk` 为空）。
- [ ] 该 key 的**授权 IP** 需包含服务器出口 IP（云服务器公网 IP）。
- [ ] 配额：确认 WebService 日调用量满足门店数 × 用户数的量级。

> 小程序内 `<map>` 组件**不需要 key**（底层由微信客户端渲染），
> 因此不存在「把 key 写进小程序」这一步。

## 六、设计规范约束

严格遵循 `user-h5/docs/design-system.md`：

- 颜色/字号/圆角/阴影只取 token，禁止字面量（白色前景与 `rgba()` 阴影除外）。
- 间距只取 `4/8/12/16/20/24/32/40rpx`；单位一律 `rpx`。
- 骨架采用「三段式固定型」：根节点 `100vh + overflow: hidden`，滚动区 `flex: 1; min-height: 0`。
- 可点击元素补 `aria-role="button"` 与语义化 `aria-label`；
  未接入功能统一 `wx.showToast({ icon: 'none' })`。
- 图标一律 Lucide，运行时引用 `assets/icons/lucide/*.svg`，新增先改 `scripts/sync-lucide-icons.mjs`。

## 七、验收标准

在 `user-h5` 目录执行：

```powershell
npm run icons      # 若新增图标
npm run check
npm run test:acceptance
```

服务端编译：

```powershell
mvn -q -pl server -am compile
```

人工验收：

1. 点单页门店选择层**无假地图 Banner**，首屏即见门店列表与导航按钮。
2. 点任一门店导航按钮 → 真实腾讯地图打开，气泡为正确门店名与地址。
3. 切换城市（长沙/广州/深圳）→ 列表与导航坐标同步正确。
4. 无门店/无缓存场景 → 不出现默认坐标 `28.2282,112.9388` 导致的错误定位。
5. `GET /api/v1/app/geo/**` 返回正常，且响应体中**不含 key/sk**。
6. `AppConfigController` 读 `tencent_map_key` 仍返回「配置不存在」。
7. Console 无新增异常；`webapi_getwaasyncconfiginfo:fail ""` 若复现，
   按 `user-h5/docs/development-tool-troubleshooting.md` 隔离记录，**不得屏蔽 Console**。

## 八、不做的事

- 不在小程序端内嵌 `<map>`（Skyline 下会白屏，历史已踩坑）。
- 不在 `user-h5` 任何文件写入 key/sk（包括 `config.js`）。
- 不改 `app.json` 的 Skyline / glass-easel 配置。
- 不删除 `pages/store-map/store-map`（webview 渲染，日后恢复站内真地图的唯一可用载体）。
- 不引入第三方地图 UI 库、远程 CDN、外部字体。

---

## 九、执行记录（2026-09-23）

### 已完成

**第 1 层：地图体验修正**

| 文件 | 改动 |
| --- | --- |
| `user-h5/pages/menu/menu.wxml` | 删除 129–147 行「假地图」占位块（`store-picker__map--entry`） |
| `user-h5/pages/menu/menu.wxss` | 删除 8 个失效选择器；`.store-picker__panel` 的 `top: 770rpx` 改为 `0`（原值是为让位已删除的 Banner）；`.store-picker__filter` 补导航栏避让 |
| `user-h5/app.wxss` | 新增尺寸 token `--nav-bar-height: 88rpx`（与 navigation-bar.js 口径一致，避免硬编码间距字面量） |
| `user-h5/pages/menu/menu.js` | 删除死方法 `openStoreMap` |

**第 2 层：服务端 LBS 代理**

| 文件 | 改动 |
| --- | --- |
| `server/.../cache/GeoCodeService.java` | 抽出 `callTencent(path, params)` 统一签名与请求；新增 `reverseGeocode()`、`distanceMatrix()` |
| `server/.../cache/AppGeoController.java` | 新增 `POST /api/v1/app/geo/regeo`、`POST /api/v1/app/geo/distance` |
| `user-h5/utils/api.js` | 新增 `fetchReverseGeocode()`、`fetchStoreDistances()` |
| `user-h5/utils/store.js` | 新增 `decorateStoresWithRealDistance()`，真实距离优先、直线估算兜底 |
| `user-h5/pages/menu/menu.js` | 新增 `refreshPickerRealDistance()`，先渲染直线距离再异步替换为真实驾车距离 |

**测试**

- 更新 `store-picker-page.test.mjs` / `menu-spec-edit.test.mjs` / `acceptance-check.test.mjs` 中针对「假地图入口」的旧断言，改为断言「不得保留占位入口」。
- 新增 `scripts/store-map-tencent.test.mjs`：扫描小程序包内所有文本源码，断言不出现腾讯 Key/SK 形态；断言前端只调自己的 `/api/v1/app/geo/**`、不直连 `apis.map.qq.com`；并校验真实距离 / 直线兜底两条分支。
- 已注册进 `npm run check` 与 `npm run test:store`。

### 验证结果

```text
npm run check            # 通过
npm run test:acceptance  # 通过
mvn -o -pl server,wuling-common -am compile   # BUILD EXIT=0
```

密钥边界测试已做反向验证：临时向 `config.js` 写入 Key 后测试立即失败并指出文件，证明该守卫非空跑。

### 待人工完成（需要腾讯后台与本机权限）

1. **腾讯控制台**：确认 Key 已勾选「WebServiceAPI」产品、已开启 SN 校验、授权 IP 含服务器出口 IP。
2. **注入真实密钥**（二选一，均要求密钥不进仓库）：
   - SQL：`UPDATE app_config SET value = JSON_OBJECT('key','<Key>','sk','<SK>') WHERE config_key='tencent_map_key';`
   - 环境变量：`TENCENT_MAP_KEY` / `TENCENT_MAP_SK`
3. 注入后执行 `AppConfigCacheService#evict("tencent_map_key")` 或重启服务，使缓存失效。
4. 真机 / 开发者工具验证：门店列表出现「驾车 x.xxkm」，点击导航按钮拉起真实腾讯地图。
5. **Secret key 泄露处置**：`tPEAfvpk...` 已在对话中暴露，建议重置；本方案已确保它不会进入代码。
