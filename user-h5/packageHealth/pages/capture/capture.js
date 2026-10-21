const api = require('../../../utils/api');
const tongueProfile = require('../../../utils/tongue-profile');
const captureMedia = require('../../../utils/tongue-capture');

const MAX_IMAGE_BYTES = 2.5 * 1024 * 1024;
const REPORT_PAGE = '/packageHealth/pages/report/report';
const SOURCE_LABELS = ['拍照', '从手机相册选择'];

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
      if (captureMedia.isUserCancel(error)) return;
      const raw = captureMedia.errorMessage(error);
      console.warn('[capture] 选择图片失败', raw, {
        sourceType: effective.sourceType,
        platform
      });
      const title = legacyFirst
        ? captureMedia.describeFailure(effective.sourceType, error)
        : captureMedia.pickFailureMessage(effective.sourceType);
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
      console.warn(
        legacyFirst ? '[capture] chooseImage 失败' : '[capture] chooseMedia 失败',
        captureMedia.errorMessage(error),
        { sourceType: effective.sourceType, platform }
      );
      next(finalError => showFailure(finalError || error));
    };
    if (legacyFirst) {
      runChooseImage(error => firstFail(error, next => runChooseMedia(next)));
      return;
    }
    runChooseMedia(error => firstFail(error, next => runChooseImage(next)));
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
