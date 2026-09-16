# 微信开发者工具排查记录

## 当前项目环境

- 项目：`user-h5`
- AppID：`touristappid`
- 渲染框架：Skyline
- 组件框架：Glass-easel
- 基础库：以微信开发者工具当前稳定版本实际运行版本为准；`project.private.config.json` 中的旧版本 `3.17.3` 仅作为历史配置，不作为生产代码约束。
- 记录日期：2026-09-16

## 现象

开发者工具 Console 重复打印：

```text
webapi_getwaasyncconfiginfo:fail ""
```

项目源码中没有 `webapi_getwaasyncconfiginfo` 或对应 WebAPI 调用。该信息不是业务接口异常，不在页面代码中增加 `try/catch`、WebAPI 重写或全局 Console 过滤。

## 推荐排查步骤

1. 将微信开发者工具更新到最新稳定版。
2. 完全退出开发者工具。
3. 清除工具级 `Cache`、`Code Cache`、`GPUCache`。
4. 重新登录开发者工具并打开本项目。
5. 使用工具提供的稳定基础库运行，启动后确认页面实际运行版本与工具配置一致，不继续强制历史版本 `3.17.3`。
6. 保持 Skyline 配置不变，分别完成以下三层隔离验证：
   - 当前 `touristappid + Skyline` 项目页面。
   - 相同 AppID 和基础库下的空白页面。
   - 使用临时 WebView 副本运行同一页面。
7. 记录工具版本、基础库、AppID、复现步骤、缓存处理结果和每层验证结果。

## 结论规则

- 如果只在 `touristappid + Skyline` 出现，且空白页面也复现，则记录为开发者工具内部问题。
- 只允许忽略完全相同的 `webapi_getwaasyncconfiginfo:fail ""` 工具噪声。
- 页面、路由、接口、Mock 数据或其他 Console 异常仍必须正常处理。
- 如果临时 WebView 副本无报错，也不代表生产配置要切换到 WebView；生产仍保留 Skyline。

## routeDone webviewId not found

- 2026-09-16 出现的 `Page route 错误`、`routeDone with a webviewId 16 is not found` 发生在一次编译热重载期间。
- 开发者工具日志显示：热重载先重启 AppService，随后销毁旧页面帧 `15/16/18`，只新建页面帧 `20`；旧页面帧 `16` 随后仍尝试回传 `routeDone`，因此报错。
- 这是开发者工具热重载期间页面帧替换与路由完成事件之间的竞态，不是商品数据、页面路由或 WXSS 代码调用导致的业务异常。
- `project.private.config.json` 已关闭 `compileHotReLoad`。修改代码后使用手动编译，避免旧页面帧参与路由切换。
- 若错误已经出现在当前运行会话中，完全重新编译项目，或退出后重新打开项目，清除旧页面帧状态。
- 不应通过修改 `app.json` 的 Skyline 配置、捕获全局异常或屏蔽 Console 来隐藏该错误。

## 当前项目验证命令

在 `user-h5` 目录执行：

```powershell
npm run icons
npm run check
npm run test:acceptance
```

微信开发者工具 CLI 是否可用取决于本机安装路径；若命令不可用，应在验收记录中写明未执行原因，并继续使用开发者工具 GUI 完成编译和真机检查。

## DOMNodeRemoved mutation event

- 报错原文：`Listener added for a 'DOMNodeRemoved' mutation event`。
- 项目源码中没有监听该事件；字符串来自微信基础库 `3.17.2.wxvpkg` 内置的旧 MutationObserver polyfill。
- 当开发者工具实际运行在 WebView 渲染模式时，基础库会注册 `DOMNodeRemoved`。新版 Chromium 已移除该事件并输出渲染层错误。
- `project.private.config.json` 必须设置 `"skylineRenderEnable": true`，与 `app.json` 的 Skyline 配置保持一致。修改后需要完全关闭并重新打开开发者工具项目，仅重新编译不会切换渲染层。
- 不得在业务代码中重写 `window.addEventListener`、屏蔽 Console 或过滤其他运行时错误。
