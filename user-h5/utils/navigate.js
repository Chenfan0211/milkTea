/**
 * 统一页面跳转工具。
 *
 * 背景（踩坑记录）：微信小程序里 tabBar 页面**必须用 wx.switchTab**，
 * 用 wx.reLaunch / wx.navigateTo / wx.redirectTo 跳 tabBar 页会**静默失败**
 * —— 既不报错也不跳转，fail / complete 回调照常触发，导致
 * 「登录成功提示已显示、但页面停在原地」这类难排查的问题。
 *
 * 因此全项目跳转一律走本模块，由它按目标页是否属于 tabBar 自动选 API。
 */

// 必须与 app.json 的 tabBar.list 逐项一致；不一致会导致跳转静默失败。
const TAB_BAR_PAGES = [
  '/pages/home/home',
  '/pages/menu/menu',
  '/pages/member/member',
  '/pages/orders/orders',
  '/pages/profile/profile'
];

/** 规范化路径：补齐前导 /，去掉 query 后再比对。 */
function normalizePath(url) {
  const raw = String(url || '');
  const withoutQuery = raw.split('?')[0];
  if (!withoutQuery) return '';
  return withoutQuery.charAt(0) === '/' ? withoutQuery : '/' + withoutQuery;
}

/** 目标是否为 tabBar 页面。 */
function isTabBarPage(url) {
  return TAB_BAR_PAGES.indexOf(normalizePath(url)) !== -1;
}

/**
 * 跳到任意页面：tabBar 页用 switchTab，其余用 reLaunch。
 * @param {string} url 完整路径（可带 query；tabBar 页会忽略 query，微信不允许带参切 tab）
 * @param {Object} [options] { fail: Function, complete: Function }
 */
function go(url, options) {
  const opts = options || {};
  if (typeof wx === 'undefined') return;
  if (isTabBarPage(url)) {
    wx.switchTab({
      url: normalizePath(url),
      fail: opts.fail,
      complete: opts.complete
    });
    return;
  }
  wx.reLaunch({
    url,
    fail: opts.fail,
    complete: opts.complete
  });
}

/**
 * 返回上一页；页面栈底（无上一页）时回落到首页。
 * @param {Object} [options] { fallback: string }
 */
function back(options) {
  const opts = options || {};
  const fallback = opts.fallback || '/pages/home/home';
  const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
  if (pages && pages.length > 1) {
    wx.navigateBack({ delta: 1 });
    return;
  }
  go(fallback);
}

module.exports = {
  TAB_BAR_PAGES,
  isTabBarPage,
  normalizePath,
  go,
  back
};