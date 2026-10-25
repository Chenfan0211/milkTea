const api = require('../../../utils/api');
const tongueProfile = require('../../../utils/tongue-profile');
const captureMedia = require('../../../utils/tongue-capture');
const featurePolicy = require('../../../utils/feature-policy');

const MAX_IMAGE_BYTES = 2.5 * 1024 * 1024;
const REPORT_PAGE = '/packageHealth/pages/report/report';
const SOURCE_LABELS = ['拍照', '从手机相册选择'];

/** 触发微信官方隐私授权弹窗；旧基础库不拦截隐私接口时直接放行。 */
function ensurePrivacyAuthorize() {
  return new Promise((resolve, reject) => {
    if (typeof wx === 'undefined' || typeof wx.requirePrivacyAuthorize !== 'function') {
      resolve();
      return;
    }
    wx.requirePrivacyAuthorize({ success: resolve, fail: reject });
  });
}

/** 记录隐私声明状态，便于区分后台未声明和用户拒绝授权。 */
function logPrivacySetting() {
  if (typeof wx === 'undefined' || typeof wx.getPrivacySetting !== 'function') return;
  wx.getPrivacySetting({
    success(res) {
      console.log('[capture] 隐私设置', {
        needAuthorization: Boolean(res && res.needAuthorization),
        privacyContractName: (res && res.privacyContractName) || ''
      });
    },
    fail(error) {
      console.warn('[capture] 获取隐私设置失败', captureMedia.errorMessage(error));
    }
  });
}

/** 打开系统设置并重新读取相机 scope。 */
function openCameraSetting() {
  return new Promise(resolve => {
    if (typeof wx === 'undefined' || typeof wx.openSetting !== 'function') {
      resolve(false);
      return;
    }
    wx.openSetting({
      success(res) {
        resolve(Boolean(res && res.authSetting && res.authSetting['scope.camera']));
      },
      fail() {
        resolve(false);
      }
    });
  });
}

/** 用户曾拒绝相机权限时，引导其主动去设置页开启。 */
function promptCameraSetting() {
  return new Promise(resolve => {
    if (typeof wx === 'undefined' || typeof wx.showModal !== 'function') {
      resolve(false);
      return;
    }
    wx.showModal({
      title: '需要相机权限',
      content: '请在设置中允许使用相机，才能拍摄舌象照片。',
      confirmText: '去设置',
      cancelText: '暂不开启',
      success(res) {
        if (!res || !res.confirm) {
          resolve(false);
          return;
        }
        openCameraSetting().then(resolve);
      },
      fail() {
        resolve(false);
      }
    });
  });
}

/** 拍照前确保 scope.camera 已授权；相册路径不会调用此流程。 */
function ensureCameraScope() {
  return new Promise((resolve, reject) => {
    if (typeof wx === 'undefined' || typeof wx.getSetting !== 'function') {
      resolve();
      return;
    }
    wx.getSetting({
      success(setting) {
        const authSetting = (setting && setting.authSetting) || {};
        const current = authSetting['scope.camera'];
        if (current === true) {
          resolve();
          return;
        }
        const rejectHandled = () => {
          reject({ errMsg: 'scope.camera denied', handled: true });
        };
        if (current === false) {
          promptCameraSetting().then(granted => (granted ? resolve() : rejectHandled()));
          return;
        }
        if (typeof wx.authorize !== 'function') {
          resolve();
          return;
        }
        wx.authorize({
          scope: 'scope.camera',
          success: resolve,
          fail(error) {
            if (captureMedia.isPrivacyDeclarationError(error) || captureMedia.isPrivacyDenied(error)) {
              reject(error);
              return;
            }
            promptCameraSetting().then(granted => (granted ? resolve() : rejectHandled()));
          }
        });
      },
      fail: reject
    });
  });
}

function getFileSize(filePath) {
  return new Promise((resolve, reject) => {
    if (typeof wx === 'undefined' || !wx.getFileInfo) {
      resolve(0);
      return;
    }
    wx.getFileInfo({
      filePath,
      success(res) {
        resolve(Number((res && res.size) || 0));
      },
      fail() {
        reject(new Error('读取图片信息失败'));
      }
    });
  });
}

function compressImage(filePath, quality) {
  return new Promise((resolve, reject) => {
    if (typeof wx === 'undefined' || !wx.compressImage) {
      resolve(filePath);
      return;
    }
    wx.compressImage({
      src: filePath,
      quality,
      success(res) {
        resolve((res && res.tempFilePath) || filePath);
      },
      fail() {
        reject(new Error('图片压缩失败，请重新拍摄'));
      }
    });
  });
}

/**
 * 拍摄舌头（参考图 2/3）。
 *
 * 与旧版舌诊表单的差异：页面上只有底部一个快门按钮，点快门先弹
 * 「拍照 / 从手机相册选择」（参考图 3 的系统选择器），选完即刻压缩并检测，
 * 不再要求用户二次确认；失败时保留当前照片，可重新点快门重拍。
 */
Page({
  data: {
    photoPath: '',
    submitting: false,
    devtoolsHint: ''
  },

  onLoad() {
    if (featurePolicy.redirectIfHidden('/packageHealth/pages/capture/capture')) return;
    const platform = wx.getSystemInfoSync ? wx.getSystemInfoSync().platform || '' : '';
    if (String(platform).toLowerCase() === 'devtools') {
      this.setData({ devtoolsHint: '开发者工具不支持直接拍照，请从相册选择' });
    }
  },

  choosePhoto() {
    if (this.data.submitting || this.picking) return;
    if (typeof wx === 'undefined') return;
    wx.showActionSheet({
      itemList: SOURCE_LABELS,
      success: res => this.pickImage((res && res.tapIndex) === 0 ? ['camera'] : ['album']),
      fail: error => {
        if (captureMedia.isUserCancel(error)) return;
        wx.showToast({ title: '未能打开拍照/相册，请重试', icon: 'none' });
      }
    });
  },

  pickImage(sourceType) {
    if (this.picking) return;
    this.picking = true;
    const platform = wx.getSystemInfoSync ? wx.getSystemInfoSync().platform || '' : '';
    const effective = captureMedia.effectiveSourceType(platform, sourceType);
    const legacyFirst = captureMedia.usesLegacyPickerFirst(platform);
    if (effective.downgraded) {
      wx.showToast({ title: '开发者工具不支持直接拍照，已为你打开相册', icon: 'none' });
    }
    const finish = () => {
      this.picking = false;
    };
    const handleFiles = res => {
      finish();
      const file = res && res.tempFiles && res.tempFiles[0];
      const filePath = (file && file.tempFilePath) || (res && res.tempFilePaths && res.tempFilePaths[0]);
      if (filePath) {
        this.handlePickedPhoto(filePath);
        return;
      }
      wx.showToast({ title: '未选择图片', icon: 'none' });
    };
    const showFailure = error => {
      finish();
      if (error && error.handled) return;
      if (captureMedia.isUserCancel(error)) return;
      const raw = captureMedia.errorMessage(error);
      console.warn('[capture] 选择图片失败', raw, {
        sourceType: effective.sourceType,
        platform
      });
      if (legacyFirst && captureMedia.isPrivacyDeclarationError(error)) {
        wx.showModal({
          title: '隐私声明未配置',
          content:
            '请在微信公众平台“设置 → 服务内容声明 → 用户隐私保护指引”中声明“收集你选中的照片或视频信息”和“访问你的摄像头”。',
          showCancel: false,
          confirmText: '知道了'
        });
        return;
      }
      const title = legacyFirst
        ? captureMedia.describeFailure(effective.sourceType, error)
        : captureMedia.pickFailureMessage(effective.sourceType, error);
      wx.showToast({ title, icon: 'none' });
    };
    const runChooseImage = onFail => {
      if (typeof wx.chooseImage !== 'function') {
        onFail(new Error('chooseImage unsupported'));
        return;
      }
      wx.chooseImage({
        count: 1,
        sizeType: ['original'],
        sourceType: effective.sourceType,
        success: handleFiles,
        fail: onFail
      });
    };
    const runChooseMedia = onFail => {
      if (typeof wx.chooseMedia !== 'function') {
        onFail(new Error('chooseMedia unsupported'));
        return;
      }
      wx.chooseMedia({
        count: 1,
        mediaType: ['image'],
        sourceType: effective.sourceType,
        sizeType: ['original'],
        success: handleFiles,
        fail: onFail
      });
    };
    const firstFail = (error, next) => {
      if (captureMedia.isUserCancel(error)) {
        finish();
        return;
      }
      if (captureMedia.isPrivacyDeclarationError(error) || captureMedia.isPrivacyDenied(error)) {
        showFailure(error);
        return;
      }
      console.warn(
        legacyFirst ? '[capture] chooseImage 失败' : '[capture] chooseMedia 失败',
        captureMedia.errorMessage(error),
        { sourceType: effective.sourceType, platform }
      );
      next(finalError => showFailure(finalError || error));
    };
    const runPicker = () => {
      if (legacyFirst) {
        runChooseImage(error => firstFail(error, next => runChooseMedia(next)));
        return;
      }
      runChooseMedia(error => firstFail(error, next => runChooseImage(next)));
    };
    logPrivacySetting();
    ensurePrivacyAuthorize()
      .then(() => {
        if (effective.sourceType.indexOf('camera') >= 0) return ensureCameraScope();
        return null;
      })
      .then(runPicker)
      .catch(showFailure);
  },

  /** 压缩到 2.5MB 以内，成功后立即上传检测。 */
  handlePickedPhoto(filePath) {
    this.picking = false;
    this.setData({ photoPath: filePath });
    wx.showLoading({ title: '压缩中...', mask: true });
    compressImage(filePath, 80)
      .then(compressed => getFileSize(compressed).then(size => ({ compressed, size })))
      .then(result => {
        if (result.size && result.size > MAX_IMAGE_BYTES) {
          return compressImage(result.compressed, 50).then(second =>
            getFileSize(second).then(size => ({ compressed: second, size }))
          );
        }
        return result;
      })
      .then(result => {
        if (result.size && result.size > MAX_IMAGE_BYTES) {
          throw new Error('图片仍超过 2.5MB，请重新拍摄');
        }
        wx.hideLoading();
        this.setData({ photoPath: result.compressed });
        return this.runDetection(result.compressed);
      })
      .catch(error => {
        wx.hideLoading();
        wx.showToast({ title: (error && error.message) || '图片处理失败', icon: 'none' });
      });
  },

  runDetection(filePath) {
    this.setData({ submitting: true });
    wx.showLoading({ title: '检测中...', mask: true });
    // 优先使用弹层保存的本地资料；直接进入拍摄页时再回退账号资料或默认值
    return tongueProfile
      .resolveTongueProfile()
      .then(profile => {
        if (!tongueProfile.hasTongueProfile(profile)) {
          throw new Error('请先在舌诊页完善个人信息');
        }
        return api.detectTongue({
          filePath,
          age: tongueProfile.ageFromBirthYear(profile.birthYear),
          sex: profile.gender
        });
      })
      .then(result => {
        const id = result && result.id;
        if (!id) throw new Error('检测结果缺少报告编号');
        wx.hideLoading();
        this.setData({ photoPath: '' });
        // 用 redirectTo：报告页返回时直接回到体质健康管理，不会带回照片重复提交
        wx.redirectTo({ url: `${REPORT_PAGE}?id=${id}` });
      })
      .catch(error => {
        wx.hideLoading();
        wx.showToast({ title: (error && error.message) || '检测失败，请稍后重试', icon: 'none' });
      })
      .then(() => this.setData({ submitting: false }));
  }
});
