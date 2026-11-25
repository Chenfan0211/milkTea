const loginGuard = require('../../utils/login-guard');
const { withShare } = require('../../utils/share');
const { ensureBusinessRole } = require('../../utils/role-page');
const { getCurrentBusinessRole } = require('../../utils/roles');
const { fetchSettleCards, bindSettleCard, setDefaultSettleCard, fetchOnboardStatus } = require('../../utils/api');

const SETTLE_CARD_STATUS = {
  PENDING: { label: '校验中', tone: 'pending' },
  PASS: { label: '已校验', tone: 'success' },
  FAIL: { label: '校验失败', tone: 'failed' }
};

/** 主体类型 → 允许的账户类型（易宝同名约束） */
const ALLOWED_ACCOUNT_TYPES = {
  ENTERPRISE: ['PUBLIC'],
  INDIVIDUAL: ['PUBLIC', 'PRIVATE'],
  MICRO: ['PRIVATE']
};

Page(
  withShare({
    data: {
      ready: false,
      role: null,
      subjectId: null,
      title: '结算卡',
      cards: [],
      cardsEmpty: true,
      accountTypes: [],
      accountType: '',
      accountTypeLabel: '',
      onboarded: false,
      form: {
        accountName: '',
        bankCardNo: '',
        bankCode: '',
        branchCode: '',
        bankName: ''
      },
      canSubmit: false,
      submitting: false
    },

    onLoad(query) {
      const fromQuery = query && query.subjectId ? Number(query.subjectId) : null;
      ensureBusinessRole().then(() => this.syncRole(fromQuery));
    },

    onShow() {
      if (this.data.ready) this.loadCards();
    },

    syncRole(fromQuery) {
      const role = getCurrentBusinessRole();
      const subjectId = fromQuery || (role && role.id);
      if (!subjectId) {
        this.setData({ ready: false });
        wx.showToast({ title: '请先开通并选择经营角色', icon: 'none' });
        return;
      }
      this.setData({ ready: true, role, subjectId }, () => {
        this.loadOnboardStatus();
        this.loadCards();
      });
    },

    /**
     * 主体类型决定可绑账户类型：
     * 企业只能对公，个体可对公或法人卡，小微只能本人卡。
     * 在本地先拦一次，避免提交后被易宝驳回浪费审核时效。
     */
    loadOnboardStatus() {
      const subjectId = this.data.subjectId;
      if (!subjectId) return;
      fetchOnboardStatus(subjectId)
        .then(status => {
          const signType = status && status.signType;
          const allowed = ALLOWED_ACCOUNT_TYPES[signType] || [];
          const accountTypes = allowed.map(value => ({
            value,
            label: value === 'PUBLIC' ? '对公账户' : '个人借记卡'
          }));
          const accountType = accountTypes.length ? accountTypes[0].value : '';
          this.setData({
            onboarded: !!(status && status.onboarded),
            accountTypes,
            accountType,
            accountTypeLabel: accountTypes.length ? accountTypes[0].label : ''
          });
        })
        .catch(() => {
          this.setData({ onboarded: false, accountTypes: [] });
        });
    },

    loadCards() {
      const subjectId = this.data.subjectId;
      if (!subjectId) return;
      fetchSettleCards(subjectId)
        .then(list => {
          const cards = (list || []).map(item => {
            const meta = SETTLE_CARD_STATUS[item.verifyStatus] || SETTLE_CARD_STATUS.PENDING;
            return { ...item, statusLabel: meta.label, statusTone: meta.tone };
          });
          this.setData({ cards, cardsEmpty: cards.length === 0 });
        })
        .catch(() => {
          this.setData({ cards: [], cardsEmpty: true });
        });
    },

    handlePickAccountType(event) {
      const value = event.currentTarget.dataset.value;
      const matched = this.data.accountTypes.find(item => item.value === value);
      this.setData({
        accountType: value,
        accountTypeLabel: matched ? matched.label : ''
      });
    },

    handleInput(event) {
      const field = event.currentTarget.dataset.field;
      if (!field) return;
      this.setData({ [`form.${field}`]: event.detail.value }, () => this.syncCanSubmit());
    },

    syncCanSubmit() {
      const f = this.data.form;
      const canSubmit =
        this.data.accountType && f.accountName && f.bankCardNo && String(f.bankCardNo).replace(/\s/g, '').length >= 12;
      this.setData({ canSubmit: !!canSubmit });
    },

    submit() {
      if (!this.data.canSubmit || this.data.submitting) {
        wx.showToast({ title: '请填写完整银行卡信息', icon: 'none' });
        return;
      }
      if (!this.data.onboarded) {
        wx.showToast({ title: '请先完成入驻与签约', icon: 'none' });
        return;
      }
      loginGuard.requirePhone(() => this.doSubmit(), { reason: '绑定结算卡需要绑定手机号' });
    },

    doSubmit() {
      const subjectId = this.data.subjectId;
      if (!subjectId) return;
      this.setData({ submitting: true });
      wx.showLoading({ title: '绑定中', mask: true });
      bindSettleCard(subjectId, {
        accountType: this.data.accountType,
        accountName: this.data.form.accountName,
        // 明文卡号：服务端负责加密入库，前端不做任何加密
        bankCardNo: String(this.data.form.bankCardNo).replace(/\s/g, ''),
        bankCode: this.data.form.bankCode,
        branchCode: this.data.form.branchCode,
        bankName: this.data.form.bankName
      })
        .then(() => {
          wx.hideLoading();
          this.setData({
            submitting: false,
            form: {
              accountName: '',
              bankCardNo: '',
              bankCode: '',
              branchCode: '',
              bankName: ''
            },
            canSubmit: false
          });
          wx.showToast({ title: '已提交绑定', icon: 'none' });
          this.loadCards();
        })
        .catch(error => {
          wx.hideLoading();
          this.setData({ submitting: false });
          wx.showToast({
            title: (error && error.message) || '绑定失败，请检查信息',
            icon: 'none'
          });
        });
    },

    setDefault(event) {
      const id = event.currentTarget.dataset.id;
      const subjectId = this.data.subjectId;
      if (!id || !subjectId) return;
      wx.showLoading({ title: '设置中', mask: true });
      setDefaultSettleCard(subjectId, id)
        .then(() => {
          wx.hideLoading();
          wx.showToast({ title: '已设为默认', icon: 'none' });
          this.loadCards();
        })
        .catch(error => {
          wx.hideLoading();
          wx.showToast({
            title: (error && error.message) || '设置失败',
            icon: 'none'
          });
        });
    }
  })
);
