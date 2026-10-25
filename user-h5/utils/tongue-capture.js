/**
 * 舌诊拍摄媒体选择兜底工具（纯函数，无 wx 依赖）。
 *
 * 背景：系统拍照/相册选择层本身由微信弹出，但 chooseMedia / chooseImage
 * 失败时旧代码用空 fail 吞掉，用户表现为「点了没反应」。这里统一判断取消、
 * 归一开发者工具来源并生成失败文案，供拍摄页与 Node 测试复用。
 */
function errorMessage(error) {
  if (!error) return '';
  return String(error.errMsg || error.message || error);
}

/** 微信取消选择时的 errMsg 均带 cancel；取消不应弹错误提示。 */
function isUserCancel(error) {
  return /cancel/i.test(errorMessage(error));
}

/**
 * 归一图片来源。
 * 开发者工具没有真实相机，选择「拍照」时降级为相册，避免 chooseMedia 静默失败。
 */
function effectiveSourceType(platform, sourceType) {
  const list = Array.isArray(sourceType) && sourceType.length ? sourceType : ['album'];
  const isDevtools = String(platform || '').toLowerCase() === 'devtools';
  if (isDevtools && list.indexOf('camera') >= 0) {
    return { sourceType: ['album'], downgraded: true };
  }
  return { sourceType: list, downgraded: false };
}

/** 开发者工具的 chooseMedia 兼容性较差，优先走遗留 chooseImage。 */
function usesLegacyPickerFirst(platform) {
  return String(platform || '').toLowerCase() === 'devtools';
}

/** 微信隐私指引未声明当前接口权限。 */
function isPrivacyDeclarationError(error) {
  return /api scope is not declared in the privacy agreement|scope is not declared in the privacy agreement/i.test(
    errorMessage(error)
  );
}

/** 用户拒绝或未完成微信隐私授权。 */
function isPrivacyDenied(error) {
  const message = errorMessage(error);
  return /privacy/i.test(message) && /(deny|denied|disagree|reject|refuse|拒绝|未同意)/i.test(message);
}

/** 相机 scope 被拒绝或授权失败。 */
function isCameraPermissionError(error) {
  const message = errorMessage(error);
  if (isPrivacyDeclarationError(error) || isPrivacyDenied(error)) return false;
  return /scope\.camera|authorize:fail|auth deny|permission denied|camera permission|相机权限/i.test(message);
}
/** 拍摄/相册失败时的用户可读文案。 */
function pickFailureMessage(sourceType, error) {
  if (isPrivacyDeclarationError(error)) return '隐私声明未配置，请联系管理员';
  if (isPrivacyDenied(error)) return '需同意隐私授权后才能使用拍照/相册';
  if (isCameraPermissionError(error)) return '相机权限未开启，请前往设置开启';
  const list = Array.isArray(sourceType) ? sourceType : [];
  return list.indexOf('camera') >= 0 ? '拍照失败，请检查相机权限' : '选择图片失败，请重试';
}

/** 在友好文案后带上真实 errMsg，供开发者工具定位问题。 */
function describeFailure(sourceType, error) {
  const friendly = pickFailureMessage(sourceType, error);
  const raw = errorMessage(error);
  return raw ? friendly + '（' + raw + '）' : friendly;
}

module.exports = {
  errorMessage,
  isUserCancel,
  isPrivacyDeclarationError,
  isPrivacyDenied,
  isCameraPermissionError,
  effectiveSourceType,
  pickFailureMessage,
  usesLegacyPickerFirst,
  describeFailure
};
