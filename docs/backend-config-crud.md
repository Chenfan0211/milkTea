# 后台配置类接口（方案乙：通用 CRUD）— 系统配置 + 主体管理

> 范围：第 1 批（系统配置）+ 第 2 批（主体管理）
> 方式：方案乙（通用 CRUD，白名单机制）

## 一、为什么用通用 CRUD

后台 42 个配置页面、62 个 store 成员，绝大多数是「结构相近的增删改查」。
若为每个实体写独立 REST 需要 60+ 个接口；改用白名单驱动的通用 CRUD 后：

- 后端只需 1 个控制器（6 个端点）
- 前端 `adminStore` 的 `add/update/remove` 自动切换到接口，**页面代码零改动**
- 接口数量从 60+ 降到 6

## 二、后端实现

### 1. 资源白名单 `CrudRegistry`

登记「资源名 → 表名 / 可写字段 / 可搜索字段 / 排序」：

| 资源名 | 表 | 说明 |
|--------|-----|------|
| `dictEntries` | sys_dict_item | 数据字典 |
| `cities` / `provinces` | region | 城市 / 省份 |
| `features` | feature_flag | 功能开关 |
| `subjects` | biz_subject | 经营主体 |
| `storeTypes` | sys_dict_item | 门店类型 |
| `users` | app_user | 小程序用户 |

### 2. 安全设计（重点）

| 风险 | 防护 |
|------|------|
| SQL 注入 | 表名/列名**只取自白名单**，值全部走占位符绑定 |
| 越权改敏感列 | 只写 `writable` 白名单内的列，`id`/`deleted`/`create_time` 不可写 |
| 访问未登记资源 | 返回 404「不支持的资源」 |
| 未授权访问 | 沿用 JWT，`/api/v1/admin/**` 必须登录 |

### 3. 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/crud/meta/resources` | 资源清单 |
| GET | `/api/v1/admin/crud/{resource}` | 分页（支持白名单字段模糊搜索） |
| GET | `/api/v1/admin/crud/{resource}/{id}` | 详情 |
| POST | `/api/v1/admin/crud/{resource}` | 新增 |
| PUT | `/api/v1/admin/crud/{resource}/{id}` | 更新 |
| DELETE | `/api/v1/admin/crud/{resource}/{id}` | 逻辑删除 |

### 4. 主体绑定关系接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST/DELETE | `/subject/binding/store/{id}/investor[/{investorId}]` | 门店 ↔ 投资人 |
| POST/DELETE | `/subject/binding/channel/{id}/store/{storeId}` | 渠道 ↔ 门店 |
| POST/DELETE | `/subject/binding/subject/{id}/user[/{userId}]` | 主体 ↔ 用户 |
| POST/DELETE | `/subject/binding/user/{uid}/role/{role}/subject/{sid}` | 用户 ↔ 业务角色 |
| POST | `/subject/binding/application/{id}/review` | 角色申请审核 |
| GET | `/subject/binding/summary` | 主体用量概览 |
| GET | `/subject/binding/list/{type}` | 按类型查主体 |

## 三、前端实现

### 1. `adminStore` 双模 CRUD

```ts
const REMOTE_RESOURCES = {
  dictEntries: 'dictEntries', cities: 'cities', provinces: 'provinces',
  features: 'features', subjects: 'subjects', storeTypes: 'storeTypes', users: 'users'
};
```

- `add/update/remove`：登记过的 key 自动走接口，其余继续本地
- `update` 采用**乐观更新 + 失败回滚**，避免「界面已改、库里没改」
- 新增 `loadRemote(key)` / `loadRemoteAll(keys)` / `isRemote(key)`

### 2. `AdminListPage` 统一预加载

配置里声明 `remoteKey`，组件挂载时先拉远端再加载列表（一处改动覆盖所有页面）：

```ts
const config: AdminListConfig = {
  title: '数据字典',
  remoteKey: 'dictEntries',
  ...
};
```

已接入的 9 个页面：`system/dict`、`system/city`（含 `remoteDeps: ['provinces']`）、`system/feature`、`subject/store|channel|investor|platform|supplier`。

### 3. 配置修正

| 项 | 原值 | 新值 | 原因 |
|----|------|------|------|
| `VITE_SERVICE_SUCCESS_CODE` | `0000` | `0` | 后端统一返回 `code:0`，不改会导致所有请求被判失败 |
| `.env.test` BASE_URL | Apifox mock | `http://127.0.0.1:8080` | 指向本地后端 |
| `.env.prod` BASE_URL | Apifox mock | `https://api.wulingshiguang.top` | 生产域名 |

## 四、验证结果

### CRUD 读
```
dictEntries -> total=4   字段: id,dictTypeId,dictType,itemCode,itemName,sort
cities      -> total=5
provinces   -> total=5
features    -> total=4
subjects    -> total=15
users       -> total=2
```

### CRUD 写 + 安全
```
新增       : code=0 id=5 name=CRUD测试项 sort=99
更新       : code=0 name=CRUD测试项-已改 sort=88
越权尝试   : 提交 {"deleted":0,"id":99999} -> id 仍为 5，deleted 未被修改 ✅
未登记资源 : /crud/sys_user -> 404 不支持的资源 ✅
逻辑删除   : code=0，删除后 total 回到 4 ✅
搜索过滤   : name=星沙 -> total=1 星沙乐运魔方店 ✅
```

### 主体绑定
```
用量概览   : 主体=15 门店=5 渠道=3 投资人=3 供应商=3
按类型查询 : STORE=5 CHANNEL=3 INVESTOR=3 SUPPLIER=3 PLATFORM=1
渠道绑门店 : 绑定成功，重复绑定返回 400 ✅
          解绑后门店数 2 -> 1 ✅
门店绑投资人: 绑定成功，投资人侧可见「长沙高铁南站店」✅
```

### 回归
| 项目 | 结果 |
|------|------|
| 后台 `vue-tsc` | 通过 |
| 后台 `oxlint` | 0 error |
| 后端 `mvn test` | 16/16 通过 |
| 数据库一致性校验 | 3/3 通过 |
| 小程序 `npm run check` | 22 项通过 |

## 五、本批修复的缺陷

1. **成功码不匹配（严重）**：前端 `VITE_SERVICE_SUCCESS_CODE=0000`，后端返回 `0`，导致所有响应被判为失败。已改为 `0`。
2. **路由歧义（重复踩坑）**：`/crud/_resources` 被 `/{resource}/{id}` 吃掉，报 `NoResourceFoundException`。已改到 `/crud/meta/resources`。
3. **表结构约束阻塞写入**：`sys_dict_item.dict_type_id` NOT NULL 但表单不传，新增直接 500。V8 迁移改为可空（以 `dict_type` 为准）；同时 `extra` JSON → VARCHAR、`region.level` 给默认值、`biz_subject.status` 给默认值。

## 六、尚未接入（第 3、4 批）

| 批次 | 模块 | 页面数 | 主要内容 |
|------|------|--------|---------|
| 第 3 批 | 商品配置 | 4 | 分类 / 规格 / 分账规则 / 商品编辑 |
| 第 4 批 | 营销配置 | 10 | 优惠券 / 储值 / 积分规则 / 礼品卡 |

其余模块（授权管理、审计日志、财务资金池/对账、审核）按需后续补充。

## 七、改动文件

**后端新增**
- `common/.../system/crud/CrudRegistry.java`
- `common/.../system/crud/CrudService.java`
- `common/.../system/crud/CrudController.java`
- `subject/controller/SubjectBindingController.java`
- `db/migration/V8__crud_schema_fix.sql`

**前端新增**
- `src/service/api/crud.ts`

**前端修改**
- `src/store/modules/admin/index.ts`（双模 CRUD + loadRemote）
- `src/views/_shared/types.ts`（remoteKey / remoteDeps）
- `src/views/_shared/AdminListPage.vue`（远端预加载）
- 9 个配置页面（remoteKey 声明）
- `.env`、`.env.test`、`.env.prod`（成功码 + 后端地址）
