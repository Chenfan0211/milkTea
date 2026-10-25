/**
 * 统一页面跳转工具。
 *
 * tabBar 页面必须用 wx.switchTab；普通页面用 navigateTo 保留返回栈，
 * 启动页等需要清栈的场景继续使用 go() 的 reLaunch 语义。
 */
const featurePolicy = require('./feature-policy');

function getTabBarPages() {
  return featurePolicy.getTabBarPages();
}

/** 规范化路径：补齐前导 /，去掉 query 后再比对。 */
function normalizePath(url) {
  const raw = String(url || '');
  const withoutQuery = raw.split('?')[0];
  if (!withoutQuery) return '';
  return withoutQuery.charAt(0) === '/' ? withoutQuery : '/' + withoutQuery;
}

/** 目标是否为当前构建策略下的 tabBar 页面。 */
function isTabBarPage(url) {
  return getTabBarPages().indexOf(normalizePath(url)) !== -1;
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
 * 打开普通页面或切换 tab。普通页面保留返回栈，tab 页面走 switchTab。
 * @param {string} url 页面路径
 * @param {Object} [options] { fail: Function, complete: Function }
 */
function open(url, options) {
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
  wx.navigateTo({
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

const navigate = {
  getTabBarPages,
  isTabBarPage,
  normalizePath,
  go,
  open,
  back
};

// 兼容旧调用：每次读取时按当前运行时策略重新计算 Tab 清单。
Object.defineProperty(navigate, 'TAB_BAR_PAGES', {
  enumerable: true,
  get: getTabBarPages
});

module.exports = navigate;
