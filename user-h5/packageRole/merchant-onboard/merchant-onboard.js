const loginGuard = require('../../utils/login-guard');
const { withShare } = require('../../utils/share');
const { ensureBusinessRole } = require('../../utils/role-page');
const { getCurrentBusinessRole } = require('../../utils/roles');
const {
  submitMerchantOnboard,
  fetchOnboardStatus,
  refreshOnboardStatus,
  markOnboardSigned
} = require('../../utils/api');

const SETTLE_CARD_URL = '/packageRole/settle-card/settle-card';
const ROLE_CENTER_URL = '/packageRole/role-center/role-center';

/**
 * 主体类型选项。
 *
 * <p>三类的资料要求完全不同（企业/个体需营业执照，小微只要身份证），
 * 由 signType 驱动表单分支，避免让自然人填营业执照这种无意义字段。
 */
const SIGN_TYPES = [
  {
    value: 'ENTERPRISE',
    label: '企业',
    desc: '需营业执照 + 法人证件，结算卡须为对公账户',
    licenceRequired: true
  },
  {
    value: 'INDIVIDUAL',
    label: '个体工商户',
    desc: '需营业执照 + 法人证件，可绑定法人借记卡',
    licenceRequired: true
  },
  {
    value: 'MICRO',
    label: '自然人（小微）',
    desc: '仅需身份证，绑定本人借记卡，有单笔限额',
    licenceRequired: false
  }
];

Page(
  withShare({
    data: {
      ready: false,
      role: null,
      title: '入驻与签约',
      signTypes: SIGN_TYPES,
      signType: '',
      licenceRequired: false,
      form: {
        legalName: '',
        legalLicenceNo: '',
        licenceNo: '',
        signName: '',
        shortName: '',
        contactName: '',
        contactMobile: '',
        contactEmail: '',
        province: '',
        city: '',
        district: '',
        address: '',
        legalLicenceFrontUrl: '',
        legalLicenceBackUrl: '',
        licenceUrl: ''
      },
      status: null,
      statusMessage: '',
      canSubmit: false,
      submitting: false
    },

    onLoad() {
      ensureBusinessRole().then(() => this.syncRole());
    },

    onShow() {
      if (this.data.ready) this.loadStatus();
    },

    syncRole() {
      const role = getCurrentBusinessRole();
      if (!role) {
        this.setData({ ready: false });
        wx.showToast({ title: '请先开通并选择经营角色', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      this.setData({ ready: true, role, title: `${role.label}入驻签约` }, () => this.loadStatus());
    },

    /** 拉取入驻状态；已入驻完成时展示结果页而非表单 */
    loadStatus() {
      const role = this.data.role;
      if (!role) return;
      fetchOnboardStatus(role.id)
        .then(data => {
          this.setData({
            status: data || null,
            statusMessage: (data && data.message) || '',
            signType: (data && data.signType) || this.data.signType
          });
          if (data && data.signType) {
            const matched = SIGN_TYPES.find(item => item.value === data.signType);
            this.setData({ licenceRequired: matched ? matched.licenceRequired : true });
          }
        })
        .catch(() => {
          // 未提交过时后端可能返回空，按「未入驻」处理，不打断用户填表
          this.setData({ status: null, statusMessage: '' });
        });
    },

    handlePickSignType(event) {
      const value = event.currentTarget.dataset.value;
      const matched = SIGN_TYPES.find(item => item.value === value);
      if (!matched) return;
      this.setData({
        signType: value,
        licenceRequired: matched.licenceRequired
      });
    },

    handleInput(event) {
      const field = event.currentTarget.dataset.field;
      if (!field) return;
      this.setData({ [`form.${field}`]: event.detail.value }, () => this.syncCanSubmit());
    },

    /** 证件照片选择：上传到文件服务后回填 URL */
    handlePickImage(event) {
      const field = event.currentTarget.dataset.field;
      if (!field) return;
      wx.chooseMedia({
        count: 1,
        mediaType: ['image'],
        sourceType: ['album', 'camera'],
        sizeType: ['compressed'],
        success: res => {
          const file = res.tempFiles && res.tempFiles[0];
          if (!file) return;
          this.uploadImage(field, file.tempFilePath);
        }
      });
    },

    /**
     * 上传证件照。
     *
     * <p>走 file-service 的 {@code /api/v1/files/images}（multipart），
     * 该接口会重编码图片并返回可公开读取的 URL。
     *
     * <p>为什么不用 request：小程序 request 不支持文件流。
     */
    uploadImage(field, filePath) {
      const config = require('../../config');
      const { readToken } = require('../../utils/request');
      wx.showLoading({ title: '上传中', mask: true });
      wx.uploadFile({
        url: `${config.BASE_URL}/api/v1/files/images`,
        filePath,
        name: 'file',
        header: { Authorization: `Bearer ${readToken()}` },
        success: res => {
          wx.hideLoading();
          let body = null;
          try {
            body = JSON.parse(res.data);
          } catch (e) {
            body = null;
          }
          const ok = body && (body.code === 0 || body.code === 200);
          if (!ok) {
            wx.showToast({ title: (body && body.message) || '上传失败', icon: 'none' });
            return;
          }
          const url = (body.data && (body.data.url || body.data.path)) || '';
          if (!url) {
            wx.showToast({ title: '上传返回地址为空', icon: 'none' });
            return;
          }
          this.setData({ [`form.${field}`]: url }, () => this.syncCanSubmit());
        },
        fail: () => {
          wx.hideLoading();
          wx.showToast({ title: '上传失败，请重试', icon: 'none' });
        }
      });
    },

    syncCanSubmit() {
      const f = this.data.form;
      const required = [
        f.legalName,
        f.legalLicenceNo,
        f.signName,
        f.contactMobile,
        f.legalLicenceFrontUrl
      ];
      if (this.data.licenceRequired) {
        required.push(f.licenceUrl, f.licenceNo);
      }
      const canSubmit = this.data.signType && required.every(v => v && String(v).trim());
      this.setData({ canSubmit: !!canSubmit });
    },

    submit() {
      if (!this.data.canSubmit || this.data.submitting) {
        if (!this.data.signType) {
          wx.showToast({ title: '请先选择主体类型', icon: 'none' });
        } else {
          wx.showToast({ title: '请填写完整必填信息', icon: 'none' });
        }
        return;
      }
      loginGuard.requirePhone(() => this.doSubmit(), { reason: '入驻需要绑定手机号' });
    },

    doSubmit() {
      const role = this.data.role;
      if (!role) return;
      this.setData({ submitting: true });
      wx.showLoading({ title: '提交中', mask: true });
      submitMerchantOnboard(role.id, {
        signType: this.data.signType,
        ...this.data.form
      })
        .then(() => {
          wx.hideLoading();
          this.setData({ submitting: false });
          wx.showToast({ title: '已提交，等待审核', icon: 'none' });
          this.loadStatus();
        })
        .catch(error => {
          wx.hideLoading();
          this.setData({ submitting: false });
          wx.showToast({
            title: (error && error.message) || '提交失败，请稍后重试',
            icon: 'none'
          });
        });
    },

    /** 打开易宝电子签章页面 */
    openSign() {
      const url = this.data.status && this.data.status.signUrl;
      if (!url) {
        wx.showToast({ title: '签署地址尚未下发，请稍后刷新', icon: 'none' });
        return;
      }
      // 易宝签署页是 H5；小程序内需用 web-view 承载，
      // 这里先复制链接给出明确指引，避免直接跳转失败。
      wx.setClipboardData({
        data: url,
        success: () => {
          wx.showToast({ title: '签署链接已复制，请在浏览器打开', icon: 'none' });
        }
      });
      const role = this.data.role;
      if (role) markOnboardSigned(role.id).catch(() => {});
    },

    refreshStatus() {
      const role = this.data.role;
      if (!role) return;
      wx.showLoading({ title: '刷新中', mask: true });
      refreshOnboardStatus(role.id)
        .then(() => {
          wx.hideLoading();
          this.loadStatus();
          wx.showToast({ title: '已刷新', icon: 'none' });
        })
        .catch(() => {
          wx.hideLoading();
          wx.showToast({ title: '刷新失败', icon: 'none' });
        });
    },

    openSettleCard() {
      const role = this.data.role;
      if (!role) return;
      wx.navigateTo({ url: `${SETTLE_CARD_URL}?subjectId=${role.id}` });
    },

    leaveToRoleCenter() {
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
      if (pages.length > 1) {
        wx.navigateBack({ delta: 1 });
        return;
      }
      wx.reLaunch({ url: ROLE_CENTER_URL });
    }
  })
);
