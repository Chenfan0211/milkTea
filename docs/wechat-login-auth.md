# 微信登录 + 鉴权细化

> 模式：**真模式**（真实调用微信 `code2session`，不做模拟降级）
> 保护范围：**仅用户相关接口**（菜单/门店/商品等保持公开）

## 一、安全前置：凭据不入库

- AppSecret 通过环境变量 `WX_APP_SECRET` 注入，**不写入代码仓库**
- `.gitignore` 已追加 `application-local.yml` / `application-prod.yml`
- 未配置 AppSecret 时**直接报错**，不静默降级（避免生产误用）

```bash
# 启动示例
set WX_APP_SECRET=<your-secret> && java -jar server-0.1.0-SNAPSHOT.jar
```

> ⚠️ 注意：本仓库的 `.env` 已被 git 跟踪。若曾提交过敏感信息，建议改用环境变量并清理历史。

## 二、登录流程

```
小程序 wx.login() → code
        ↓
POST /api/v1/app/auth/wx-login { code }        （公开）
        ↓
后端 code2session → openid / unionid / session_key
        ↓
按 openid 查/建 app_user → 签发 mini JWT（type=mini）
        ↓
session_key 存 Redis（12h），不下发给前端
        ↓
前端存 token，后续请求自动带 Authorization: Bearer <token>
        ↓
受保护接口由拦截器解析 token，userId 从 token 取
```

## 三、新增接口

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| POST | `/api/v1/app/auth/wx-login` | 公开 | code 换 token |
| GET | `/api/v1/app/auth/me` | 需登录 | 当前用户资料 |
| POST | `/api/v1/app/auth/phone` | 需登录 | 解密绑定手机号 |
| POST | `/api/v1/app/auth/profile` | 需登录 | 解密更新头像昵称 |
| POST | `/api/v1/app/auth/location` | 需登录 | 上报定位 |

## 四、鉴权保护范围

**需登录**（`MiniAppAuthInterceptor`）：
订单与支付、用户资料与优惠券、储值订单/充值、我的礼品卡与购买、时光币（记录/签到/兑换）、提现、评论、角色工作台、账号操作（me/phone/profile/location）

**保持公开**：门店、菜单、商品详情、门店类型、优惠券模板、会员等级

## 五、越权修复（本批核心安全收益）

改造前：接口从**前端传参**取 `userId`，任何人改 ID 就能看他人数据。

改造后：**userId 一律从 JWT 解析**，前端传参被忽略或校验。

| 接口 | 改造前 | 改造后 |
|------|--------|--------|
| `GET /app/orders` | `?userId=任意` | 从 token 取 |
| `POST /app/orders` | body 带 userId | 强制覆盖为 token 用户 |
| `GET /app/orders/{orderNo}` | 无归属校验 | 校验归属，非本人 403 |
| `GET /app/users/{userId}` | 直接返回他人资料 | 非本人 403 |
| `GET /app/users/{userId}/coupons` | 可查他人券 | token 取 userId |
| `POST /app/points/*` | 可替他人签到/兑换 | token 取 userId |
| `POST /app/withdrawals` | 可替他人提现 | token 取 userId |
| `POST /app/comments` | 可冒名评论 | token 取 userId |

## 六、验证结果

### 鉴权矩阵（16/16 通过）
```
auth/me（后台路径）        status=401 code=8888   ✅ 后台 token 不能访问小程序接口
mini auth/me              status=200 code=0      ✅
my orders                 status=200 code=0      ✅
my coupons                status=200 code=0      ✅
points records            status=200 code=0      ✅
my gift cards             status=200 code=0      ✅
stored orders             status=200 code=0      ✅
my withdrawals            status=200 code=0      ✅
workbench overview        status=200 code=0      ✅
other user                status=200 code=403    ✅ 越权被拒
no token                  status=401 code=8888   ✅
bad token                 status=401 code=8888   ✅
public menu               status=200 code=0      ✅
public stores             status=200 code=0      ✅
public coupons            status=200 code=0      ✅
public member-levels      status=200 code=0      ✅
```

### 真实微信调用验证
```
POST /auth/wx-login { code: "invalid_test_code" }
-> 400 微信登录失败（40029），请重试
```
**`40029` 是微信服务端真实返回的错误码**（invalid code），证明：
- 后端成功连通微信 `code2session` 接口
- AppSecret 配置正确
- 错误处理链路正常

> 真机完整登录需部署到**已备案域名**（微信要求 request 合法域名），本地无法完成。

### 回归
| 项目 | 结果 |
|------|------|
| 后端 `mvn test` | 16/16 通过 |
| 数据库一致性校验 | 3/3 通过 |
| 小程序 `npm run check` | 22 项通过 |
| 后台 `vue-tsc` | 通过 |

## 七、本批修复的关键缺陷

**小程序 token 被后台过滤器误杀**：
`JwtAuthenticationFilter` 对所有请求尝试解析 token 并按**后台 access token** 校验，导致小程序 mini token 被判定「无效的令牌」返回 8888。

修复：覆写 `shouldNotFilter`，`/api/v1/app/**` 交由小程序拦截器处理。

> 这个缺陷不修的话，小程序端**所有需登录接口都会失败**，且错误信息具有误导性。

## 八、改动文件

**后端新增**
- `user/service/WxAuthService.java`（code2session + AES 解密）
- `user/service/MiniAppAuthService.java`（登录、绑定手机号/头像、定位）
- `user/controller/MiniAppAuthController.java`
- `user/dto/WxLoginRequest.java` / `WxLoginResponse.java` / `WxDecryptRequest.java`
- `auth/security/MiniAppTokenProvider.java`（小程序 JWT）
- `auth/security/MiniAppAuthInterceptor.java`
- `auth/security/CurrentUser.java`（ThreadLocal 上下文）
- `common/config/WebConfig.java`（拦截器注册）

**后端修改**
- `application.yml`（微信配置，secret 走环境变量）
- `SecurityConfig.java`（放行 wx-login）
- `JwtAuthenticationFilter.java`（跳过小程序路径）
- `AppMarketingController` / `AppOrderController` / `WithdrawalController`（userId 从 token 取）

**前端新增**
- `user-h5/utils/auth.js`（登录态管理）

**前端修改**
- `user-h5/utils/request.js`（自动带 token + 8888 处理）
- `user-h5/utils/api.js`（接口去 userId 参数，新增 5 个登录接口）
- `.gitignore`（忽略敏感配置）

## 九、后续待办

1. **真机联调**：部署到已备案域名后，用微信开发者工具完成真实登录
2. **小程序授权 UI**：`getPhoneNumber` / `getUserProfile` / `getLocation` 按钮需在页面中接入（`utils/auth.js` 已备好底层方法）
3. **session_key 续期**：当前 12h 过期，用户长期不活跃需重新登录
