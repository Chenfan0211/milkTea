const { withShare } = require('../../utils/share');
const { getCurrentBusinessRole, getIncomeData } = require('../../utils/roles');

const ROLE_CENTER_URL = '/pages/role-center/role-center';
const RECORDS_URL = '/pages/role-income-records/role-income-records';
const RULES_URL = '/pages/role-income-rules/role-income-rules';

// 趋势条形宽度：按区间内最大值归一，最小保留 8% 保证可见。
function withPercent(trend) {
  const values = (trend || []).map(item => Number(String(item.value).replace(/[^\d.]/g, '')) || 0);
  const max = Math.max.apply(null, values.concat([0]));
  return (trend || []).map((item, index) => {
    const percent = max > 0 ? Math.round((values[index] / max) * 100) : 0;
    return Object.assign({}, item, { percent: percent < 8 ? 8 : percent });
  });
}

Page(
  withShare({
    data: {
      ready: false,
      role: null,
      title: '收益明细',
      income: null,
      metricLabel: '收益',
      metricValue: '¥0.00',
      summaryLabel: '累计',
      summaryValue: '¥0.00',
      recordsSummary: '暂无收益记录'
    },
    onLoad() {
      this.syncRole();
    },
    onShow() {
      if (this.data.ready) this.syncIncome();
    },
    syncRole() {
      const role = getCurrentBusinessRole();
      if (!role) {
        this.setData({ ready: false, role: null, title: '收益明细' });
        wx.showToast({ title: '请先开通并选择经营角色', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      const income = getIncomeData(role.id);
      if (!income) {
        this.setData({ ready: false, role, title: '收益明细' });
        wx.showToast({ title: '当前角色暂无收益数据', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      this.setData({ ready: true, role, title: `${role.label}${income.title}` }, () => this.syncIncome());
    },
    syncIncome() {
      const income = getIncomeData(this.data.role.id);
      if (!income) return;
      const records = income.records || [];
      const pendingCount = records.filter(item => item.status === 'pending').length;
      const primary = income.today || income.month || '';
      this.setData({
        income: Object.assign({}, income, { trend: withPercent(income.trend) }),
        metricLabel: income.metricLabel || income.title || '收益',
        metricValue: primary,
        summaryLabel: income.today ? '本月累计' : '累计收益',
        summaryValue: income.today ? income.month || income.total : income.total,
        recordsSummary: records.length
          ? `共 ${records.length} 笔${pendingCount ? `，${pendingCount} 笔待结算` : ''}`
          : '暂无收益记录'
      });
    },
    openRecords() {
      wx.navigateTo({ url: RECORDS_URL });
    },
    openRules() {
      wx.navigateTo({ url: RULES_URL });
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