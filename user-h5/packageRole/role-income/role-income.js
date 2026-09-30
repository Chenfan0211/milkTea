const { withShare } = require('../../utils/share');
const {
  getCurrentBusinessRole,
  getIncomeData,
  syncIncomeFromRemote,
  syncWorkbenchFromRemote,
  syncRolesFromRemote
} = require('../../utils/roles');

const ROLE_CENTER_URL = '/packageRole/role-center/role-center';
const RECORDS_URL = '/packageRole/role-income-records/role-income-records';
const RULES_URL = '/packageRole/role-income-rules/role-income-rules';

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
      recordsSummary: '暂无收益记录',
      loading: true,
      loadError: ''
    },
    onLoad() {
      this.loadIncome();
    },
    onShow() {
      // 从详情/记录页返回时刷新（首次进入由 onLoad 处理，避免重复请求）
      if (this.data.ready) this.refreshFromRemote();
    },
    /**
     * 收益数据来自后端结算台账 + 账户概览。
     *
     * 执行顺序（关键）：
     *   1) 先 syncRolesFromRemote —— 冷启动/直接进入时本地 Storage 尚无角色，
     *      必须先从 /roles/mine 拉取，否则 getCurrentBusinessRole() 返回 null，
     *      页面会误判为「暂无权限」（历史 bug：onLoad 只同步读本地）。
     *   2) 再并发拉工作台概览与收益台账。
     *   3) 统一渲染；失败给出重试入口，不再静默跳回角色中心。
     */
    loadIncome() {
      this.setData({ loading: true, loadError: '', ready: false });
      syncRolesFromRemote()
        .then(() => {
          const role = getCurrentBusinessRole();
          if (!role) {
            this.setData({ loading: false, ready: false, role: null, title: '收益明细' });
            return null;
          }
          this.setData({ ready: true, role, title: `${role.label}收益明细` });
          return Promise.all([syncWorkbenchFromRemote(role.id), syncIncomeFromRemote(role.id)]);
        })
        .then(() => {
          if (this.data.ready) this.syncIncome();
        })
        .catch(() => {
          this.setData({ loading: false, loadError: '收益数据加载失败' });
        });
    },
    refreshFromRemote() {
      const role = this.data.role;
      if (!role) return;
      Promise.all([syncWorkbenchFromRemote(role.id), syncIncomeFromRemote(role.id)])
        .then(() => {
          this.syncIncome();
        })
        .catch(() => {
          this.setData({ loadError: '收益数据加载失败' });
        });
    },
    syncIncome() {
      const income = getIncomeData(this.data.role.id);
      const records = income ? income.records || [] : [];
      const pendingCount = records.filter(item => item.status === 'pending').length;
      // income 为空（尚未拉到）时用空结构占位，保证页面结构仍在、只显示空值
      const safe = income || {
        title: '',
        metricLabel: '收益',
        today: '',
        month: '',
        total: '¥0.00',
        pending: '¥0.00',
        settled: '¥0.00',
        trend: [],
        records: []
      };
      const primary = safe.today || safe.month || '';
      this.setData({
        loading: false,
        loadError: '',
        income: Object.assign({}, safe, { trend: withPercent(safe.trend) }),
        metricLabel: safe.metricLabel || safe.title || '收益',
        metricValue: primary,
        summaryLabel: safe.today ? '本月累计' : '累计收益',
        summaryValue: safe.today ? safe.month || safe.total : safe.total,
        recordsSummary: records.length
          ? `共 ${records.length} 笔${pendingCount ? `，${pendingCount} 笔待结算` : ''}`
          : '暂无收益记录'
      });
    },
    /** 加载失败后重试。 */
    retryLoad() {
      this.loadIncome();
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
