/**
 * 邀请人（推荐关系）本地暂存。
 *
 * 为什么要存 Storage 而不是一路用页面参数透传：
 *   好友从分享链接进入小程序后，要经过「启动页 -> 授权页 -> （可能）短信页 -> 注册」
 *   多次跳转。中途任何一跳丢参数，推荐关系就断了，且断了之后无法恢复
 *   （用户已经完成注册，无法补挂推荐人）。
 *   因此进入时先落 Storage，注册时再取出来提交，链路更稳。
 *
 * 生命周期：
 *   · 进入带 referrerId 的分享链接时写入（captureFromOptions）；
 *   · 注册成功后清除（clear），避免影响该设备后续的其它注册；
 *   · 仅存 userId 数字，不涉用户隐私。
 */
const STORAGE_KEY = 'milkTea:referrer';

function readStorage(key) {
  try {
    return typeof wx !== 'undefined' && wx.getStorageSync ? wx.getStorageSync(key) : '';
  } catch (error) {
    return '';
  }
}

function writeStorage(key, value) {
  try {
    if (typeof wx !== 'undefined' && wx.setStorageSync) wx.setStorageSync(key, value);
  } catch (error) {
    // 存储不可用时不阻断流程（推荐关系丢失优于注册失败）
  }
}

/**
 * 从页面参数中捕获邀请人并暂存。
 *
 * 只接受正整数：分享链接可被篡改，非法值直接忽略，
 * 由后端再做一次「邀请人是否存在」的校验。
 */
function captureFromOptions(options) {
  const raw = options && options.referrerId;
  const referrerId = Number(raw);
  if (!Number.isInteger(referrerId) || referrerId <= 0) return null;
  writeStorage(STORAGE_KEY, referrerId);
  return referrerId;
}

/** 读取暂存的邀请人 userId；无则返回 null。 */
function getReferrerId() {
  const raw = readStorage(STORAGE_KEY);
  const referrerId = Number(raw);
  return Number.isInteger(referrerId) && referrerId > 0 ? referrerId : null;
}

/** 清除暂存的邀请人（注册成功后调用，避免误挂到后续注册）。 */
function clear() {
  try {
    if (typeof wx !== 'undefined' && wx.removeStorageSync) wx.removeStorageSync(STORAGE_KEY);
  } catch (error) {
    // 忽略
  }
}

module.exports = {
  STORAGE_KEY,
  captureFromOptions,
  getReferrerId,
  clear
};