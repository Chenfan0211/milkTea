const loginGuard = require('../../utils/login-guard');
const { withShare } = require('../../utils/share');
const { getCurrentBusinessRole, getWithdrawData, getWithdrawRule } = require('../../utils/roles');

const ROLE_CENTER_URL = '/pages/role-center/role-center';
const RECORDS_URL = '/pages/role-withdraw-records/role-withdraw-records';
const RULES_URL = '/pages/role-withdraw-rules/role-withdraw-rules';

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
      instantLimit: 0,
      records: [],
      recordsSummary: '暂无提现记录',
      arrivalText: '0.00',
      feeText: '0.00',
      canSubmit: false
    },
    onLoad() {
      this.syncRole();
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
      const data = getWithdrawData(role.id);
      if (!data) {
        this.setData({ ready: false, role, title: '提现' });
        wx.showToast({ title: '当前角色暂无提现数据', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      const rule = getWithdrawRule();
      this.setData(
        {
          ready: true,
          role,
          title: `${role.label}提现`,
          data,
          instantLimit: parseBalance(rule.instantLimit),
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
      this.setData({
        arrivalText: formatMoney(Math.max(amount - fee, 0)),
        feeText: formatMoney(fee),
        canSubmit: amount > 0 && amount <= balance
      });
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
      loginGuard.requirePhone(() => this.doSubmit(), { reason: '提现需要绑定手机号' });
    },

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
      const status = amount > this.data.instantLimit ? 'pending' : 'processing';
      const now = this.formatNow();
      const record = {
        id: 'w-' + Date.now(),
        orderNo: this.buildOrderNo(),
        amount: '¥' + formatMoney(amount),
        feeText: '¥0.00',
        arrivalText: '¥' + formatMoney(amount),
        channel: '微信零钱',
        status,
        time: now,
        note: status === 'pending' ? '已提交，等待后台审核' : '小额即时出款，预计 2 小时内到账',
        timeline: [
          {
            id: 'submitted',
            title: '已提交',
            description: '提现申请已提交，等待系统受理',
            state: 'done',
            time: now
          },
          {
            id: status === 'pending' ? 'auditing' : 'paying',
            title: status === 'pending' ? '审核中' : '出款中',
            description: status === 'pending' ? '后台审核中，请耐心等待' : '小额即时出款，预计 2 小时内到账',
            state: 'active',
            time: now
          },
          {
            id: status === 'pending' ? 'arrived' : 'arrived',
            title: '已到账',
            description: '款项将打入指定收款账户',
            state: 'todo',
            time: ''
          }
        ]
      };
      const nextRecords = [record].concat(
        this.data.records.map(item => Object.assign({}, item))
      );
      const nextData = Object.assign({}, this.data.data, { records: nextRecords });
      this.setData({ data: nextData, records: nextRecords, amount: '' }, () => {
        this.syncRecords();
        this.syncPreview();
      });
      wx.showToast({
        title: status === 'pending' ? '已提交，等待后台审核' : '提现申请已提交',
        icon: 'none'
      });
    },
    buildOrderNo() {
      const now = new Date();
      const pad = value => String(value).padStart(2, '0');
      const stamp =
        now.getFullYear() + pad(now.getMonth() + 1) + pad(now.getDate()) + pad(now.getHours()) + pad(now.getMinutes());
      return 'WD' + stamp + String(now.getSeconds()).padStart(2, '0').slice(-2);
    },
    formatNow() {
      const now = new Date();
      const pad = value => String(value).padStart(2, '0');
      return (
        now.getFullYear() +
        '-' +
        pad(now.getMonth() + 1) +
        '-' +
        pad(now.getDate()) +
        ' ' +
        pad(now.getHours()) +
        ':' +
        pad(now.getMinutes()) +
        ':' +
        pad(now.getSeconds())
      );
    }
  })
);

