# 投资人/经营角色「提现」页重构计划

> 创建日期：2026-09-21
> 涉及页面：`pages/role-withdraw`（重构）、`pages/role-withdraw-records`（新增）

## 一、背景与问题

当前 `pages/role-withdraw` 是首个「功能落地页」批量生成时的产物，结构为：
3 个等权数字卡 → 表单卡 → 规则文本卡 → 记录文本卡 → 开发备注。

问题：
1. 无视觉重心，四块内容同权重堆叠。
2. 提现规则是 4 行无图标裸文字，可读性差。
3. 提现记录仅有 `金额 + 状态`，WXSS 中定义的 `record-row__body/__meta/__time/__amount` 为死代码。
4. 缺少「全部提现」「到账金额预览」「按钮禁用态」等必要交互。
5. 页面底部暴露开发备注「演示数据，提现仅写入本地，需接入后端接口」。
6. 三个金额全部使用品牌绿，无颜色层级。

## 二、目标

- 提现页视觉重心明确、信息分层清晰，与 `stored-value` / `points-*` 系列页面调性一致。
- 提现规则改为「图标 + 分条卡片」呈现（页面内，不做独立页）。
- 提现记录拆分为独立页面，骨架对齐 `exchange-records`（横向状态筛选 + 卡片列表 + 空状态）。
- 记录页入口：导航栏右上角图标 + 提现页内一行列表入口。
- 清除页面内开发备注与死代码。

## 三、设计规范约束

严格遵循 `user-h5/docs/design-system.md`：

- 颜色/字号/圆角/阴影只取 token，禁止字面量（白色前景与 `rgba()` 阴影除外）。
- 间距只取 `4/8/12/16/20/24/32/40rpx`；页面左右边距 `var(--page-gutter)`。
- 单位一律 `rpx`；禁止原生 `<button>`。
- 骨架采用「三段式固定型」：根节点 `100vh + overflow: hidden`，滚动区 `flex: 1; min-height: 0`。
- 可点击元素补 `aria-role="button"` 与语义化 `aria-label`。
- 图标一律 Lucide，运行时引用 `assets/icons/lucide/*.svg`。

## 四、提现页新结构（模式 B）

```
navigation-bar（title=投资人提现，right 插槽放「提现记录」入口图标）
└ scroll-view（flex:1; min-height:0）
  └ content（padding: 24rpx 20rpx 40rpx）
    ├ ① 可提余额 Hero 卡（--brand-green 底）
    │   ├ label「可提现余额（元）」
    │   ├ value ¥1,806.00（--font-display / #FFFFFF）
    │   ├ 次级指标行：待结算 / 押金（白字 80% 透明）
    │   └ 「全部提现」白色胶囊按钮
    ├ ② 提现表单卡
    │   ├ 行：提现金额 label + ¥ 前缀 + input（type=digit）+「全部提现」
    │   ├ 预览行：本次到账 / 手续费
    │   └ 主按钮「提交提现」（80rpx 胶囊，disabled 置灰）
    ├ ③ 提现规则卡（section-heading 带 Lucide 图标 + 分条）
    │   └ 4 条规则，每条 = 图标 + 标题 + 说明
    └ ④ 提现记录入口行
        └ 左侧图标 +「提现记录」+ 右侧「已提交 N 笔」+ chevron-right
```

## 五、记录页结构（模式 B）

```
navigation-bar（title=提现记录，back）
├ scroll-view 横向状态筛选（复用 exchange-records 的 category Tab 样式）
└ scroll-view 纵向列表
  ├ 记录卡：金额（大字）+ 状态（右侧带色）+ 时间 + 备注
  └ empty-state（无记录时）
```

筛选档位：全部 / 处理中 / 审核中 / 已到账 / 已驳回。

## 六、文件改动清单

| 文件 | 操作 | 说明 |
|---|---|---|
| `pages/role-withdraw/role-withdraw.wxml` | 重写 | 新四段结构 |
| `pages/role-withdraw/role-withdraw.wxss` | 重写 | 删除死代码，新增 hero/form/rule/entry 样式 |
| `pages/role-withdraw/role-withdraw.js` | 改 | 全部提现、到账预览、按钮态、跳记录页 |
| `pages/role-withdraw/role-withdraw.json` | 不改 | 组件已齐 |
| `pages/role-withdraw-records/*` | 新增 | 4 个文件（js/json/wxml/wxss） |
| `data/role-mock.js` | 改 | `withdrawData[*].records` 补演示数据，记录字段含 id/amount/status/time/note |
| `utils/roles.js` | 改 | `getWithdrawData` 透传新字段 |
| `utils/share.js` | 改 | 新页面标题 + 私密名单 |
| `app.json` | 改 | 注册新页面 |
| `scripts/check-project.mjs` | 改 | `expectedPages` 追加新页面（否则 check 失败） |
| `scripts/role-function-pages.test.mjs` | 改 | 补新页面与记录字段断言 |
| `scripts/sync-lucide-icons.mjs` | 可能改 | 若缺图标先加映射再 `npm run icons` |

## 七、验收

```bash
cd user-h5
npm run icons      # 如新增图标
npm run check
npm run test:acceptance
```

通过标准：命令全绿；提现页与记录页在开发者工具中无 Console 报错；视觉与设计系统 token 一致。
