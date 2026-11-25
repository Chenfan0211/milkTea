const loginGuard = require('../../utils/login-guard');
const { withShare } = require('../../utils/share');
const { ensureBusinessRole } = require('../../utils/role-page');
const {
  getCurrentBusinessRole,
  getWithdrawData,
  submitWithdraw,
  syncWithdrawalsFromRemote,
  syncWithdrawRuleFromRemote,
  syncWorkbenchFromRemote
} = require('../../utils/roles');
const { fetchOnboardStatus, fetchSettleCards } = require('../../utils/api');

const ROLE_CENTER_URL = '/packageRole/role-center/role-center';
const RECORDS_URL = '/packageRole/role-withdraw-records/role-withdraw-records';
const RULES_URL = '/packageRole/role-withdraw-rules/role-withdraw-rules';
const ONBOARD_URL = '/packageRole/merchant-onboard/merchant-onboard';
const SETTLE_CARD_URL = '/packageRole/settle-card/settle-card';

function parseAmount(value) {
  const amount = Number(value);
  if (!Number.isFinite(amount) || amount <= 0) return null;
  return Math.round(amount * 100) / 100;
}

function parseBalance(value) {
  const amount = Number(String(value).replace(/[^\d.]/g, ''));
  return Number.isFinite(amount) ? amount : 0;
}

function formatMoney(value) {
  return Number(value).toFixed(2);
}

Page(
  withShare({
    data: {
      ready: false,
      role: null,
      title: '提现',
      data: null,
      amount: '',
      records: [],
      recordsSummary: '暂无提现记录',
      arrivalText: '0.00',
      feeText: '0.00',
      canSubmit: false,
      // 易宝提现前置：入驻完成 + 已绑结算卡。未满足时整卡置灰并引导补办。
      onboarded: false,
      hasSettleCard: false,
      payoutReady: false,
      payoutHint: '',
      depositText: '0.00'
    },
    onLoad() {
      // 先确保角色已同步（冷启动/直接进入时本地 Storage 尚无角色），
      // 再走原有 syncRole（内部自会按「有无角色」分流处理）。
      ensureBusinessRole().then(() => this.syncRole());
    },
    onShow() {
      if (this.data.ready) this.syncRecords();
    },
    syncRole() {
      const role = getCurrentBusinessRole();
      if (!role) {
        this.setData({ ready: false, role: null, title: '提现' });
        wx.showToast({ title: '请先开通并选择经营角色', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      // 余额与规则来自后端；拉到后重渲染，失败时保留现有数据
      Promise.all([
        syncWorkbenchFromRemote(role.id),
        syncWithdrawalsFromRemote(role.id),
        syncWithdrawRuleFromRemote()
      ]).then(() => this.applyRoleData(role));
      this.loadPayoutReadiness(role.id);
      this.applyRoleData(role);
    },
    /**
     * 查询易宝提现前置条件。
     *
     * <p>为什么单独查而不混进 workbench：入驻状态与结算卡是易宝域数据，
     * 与余额无关；即使查询失败也不应阻塞余额展示，仅置灰提交按钮。
     */
    loadPayoutReadiness(subjectId) {
      if (!subjectId) return;
      Promise.all([
        fetchOnboardStatus(subjectId).catch(() => null),
        fetchSettleCards(subjectId).catch(() => [])
      ]).then(([status, cards]) => {
        const onboarded = !!(status && status.onboarded);
        const hasSettleCard = !!(cards && cards.some(item => item.verifyStatus === 'PASS'));
        let hint = '';
        if (!onboarded) {
          hint = '请先完成入驻与协议签署';
        } else if (!hasSettleCard) {
          hint = '请先绑定结算银行卡';
        }
        this.setData({
          onboarded,
          hasSettleCard,
          payoutReady: onboarded && hasSettleCard,
          payoutHint: hint
        });
      });
    },
    /** 用当前缓存（可能为空）渲染提现页 */
    applyRoleData(role) {
      const data = getWithdrawData(role.id);
      if (!data) {
        this.setData({ ready: false, role, title: '提现' });
        wx.showToast({ title: '当前角色暂无提现数据', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      this.setData(
        {
          ready: true,
          role,
          title: `${role.label}提现`,
          data,
          records: data.records
        },
        () => {
          this.syncRecords();
          this.syncPreview();
        }
      );
    },
    syncRecords() {
      const records = (this.data.data && this.data.data.records) || [];
      const pendingCount = records.filter(item => item.status === 'pending').length;
      this.setData({
        records,
        recordsSummary: records.length
          ? `共 ${records.length} 笔${pendingCount ? `，${pendingCount} 笔审核中` : ''}`
          : '暂无提现记录'
      });
    },
    syncPreview() {
      const amount = parseAmount(this.data.amount) || 0;
      const balance = parseBalance(this.data.data ? this.data.data.balance : '');
      const fee = 0;
      // 押金来自后端 overview.depositBalance（已由 roles.js 转成元）；
      // 不可提现，仅展示，因此不参与 canSubmit 计算。
      const deposit = (this.data.data && this.data.data.deposit) || '0.00';
      this.setData({
        arrivalText: formatMoney(Math.max(amount - fee, 0)),
        feeText: formatMoney(fee),
        depositText: deposit,
        // 提现还要求易宝前置条件就绪，否则后端会拒绝（给出明确指引更好）
        canSubmit: amount > 0 && amount <= balance && this.data.payoutReady
      });
    },
    openOnboard() {
      const role = this.data.role;
      if (!role) return;
      wx.navigateTo({ url: `${ONBOARD_URL}?subjectId=${role.id}` });
    },
    openSettleCard() {
      const role = this.data.role;
      if (!role) return;
      wx.navigateTo({ url: `${SETTLE_CARD_URL}?subjectId=${role.id}` });
    },
    handleAmount(event) {
      this.setData({ amount: event.detail.value }, () => this.syncPreview());
    },
    fillAll() {
      const balance = parseBalance(this.data.data ? this.data.data.balance : '');
      if (!balance) {
        wx.showToast({ title: '当前无可提现余额', icon: 'none' });
        return;
      }
      this.setData({ amount: formatMoney(balance) }, () => this.syncPreview());
    },
    openRules() {
      wx.navigateTo({ url: RULES_URL });
    },
    openRecords() {
      wx.navigateTo({ url: RECORDS_URL });
    },
    leaveToRoleCenter() {
      const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : [];
      if (pages.length > 1) {
        wx.navigateBack({ delta: 1 });
        return;
      }
      wx.reLaunch({ url: ROLE_CENTER_URL });
    },
    submit() {
      if (!this.data.payoutReady) {
        wx.showToast({ title: this.data.payoutHint || '请先完成提现前置准备', icon: 'none' });
        return;
      }
      loginGuard.requirePhone(() => this.doSubmit(), { reason: '提现需要绑定手机号' });
    },

    /**
     * 提交提现申请（真实写库）。
     *
     * 修复说明：原实现只在本地拼一条假记录塞进列表，
     * 既没落库、也没冻结余额、更没有后台审核入口 ——
     * 用户看到「提交成功」但后台查无此单，钱也没动。
     * 现改为调用 POST /api/v1/app/withdrawals（金额单位为「分」），
     * 成功后再从后端重新拉取记录与余额，保证页面与库内一致。
     */
    doSubmit() {
      const amount = parseAmount(this.data.amount);
      const balance = parseBalance(this.data.data.balance);
      if (!amount) {
        wx.showToast({ title: '请输入有效提现金额', icon: 'none' });
        return;
      }
      if (amount > balance) {
        wx.showToast({ title: '提现金额不能超过余额', icon: 'none' });
        return;
      }
      if (this.submitting) return;
      this.submitting = true;
      wx.showLoading({ title: '提交中', mask: true });
      submitWithdraw(amount)
        .then(() => {
          wx.hideLoading();
          this.submitting = false;
          this.setData({ amount: '' });
          wx.showToast({ title: '提现申请已提交', icon: 'none' });
          // 重新拉取记录与余额，确保展示与后端一致
          const role = getCurrentBusinessRole();
          if (!role) return null;
          return Promise.all([syncWorkbenchFromRemote(role.id), syncWithdrawalsFromRemote(role.id)]).then(() =>
            this.applyRoleData(role)
          );
        })
        .catch(error => {
          wx.hideLoading();
          this.submitting = false;
          wx.showToast({ title: (error && error.message) || '提现申请失败，请稍后重试', icon: 'none' });
        });
    }
  })
);
