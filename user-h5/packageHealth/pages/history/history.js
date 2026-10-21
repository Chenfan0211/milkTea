const api = require('../../../utils/api');
const { formatDateTime } = require('../../../utils/date-format');
const auth = require('../../../utils/auth');

const REPORT_PAGE = '/packageHealth/pages/report/report';

/**
 * 体质辨识记录（参考图 4）。
 *
 * 摘要口径沿用后端「本人最近 6 个月、未删除」：
 * 列表展示「性别/年龄 + 体质 + 检测时间」，点卡片进详情。
 */
/**
 * 头部「男/23岁」：只使用该次检测的接口值（后端已下发 sex/age）；
 * 老记录缺失时返回空串，卡片只显示体质名，不再读本地缓存。
 */
function profileSummaryOf(report) {
  const parts = [];
  const sex = String((report && report.sex) || '').toLowerCase();
  if (sex === 'male') parts.push('男');
  else if (sex === 'female') parts.push('女');
  const age = Number(report && report.age);
  if (Number.isFinite(age) && age >= 0 && age <= 150) parts.push(age + '岁');
  return parts.join('/');
}

function normalizeReport(report, index) {
  const status = (report && report.reportStatus) || 'PENDING';
  const physique = (report && report.physique) || '';
  return {
    id: String((report && report.id) || index),
    profile: profileSummaryOf(report),
    physique: physique || (status === 'READY' ? '体质报告' : '体质识别中'),
    date: formatDateTime((report && report.createdAt) || ''),
    status
  };
}

Page({
  data: {
    reports: [],
    loading: false
  },

  onShow() {
    this.loadReports();
  },

  loadReports() {
    if (!auth.isLoggedIn()) {
      this.setData({ reports: [], loading: false });
      return;
    }
    this.setData({ loading: true });
    api
      .fetchTongueReports()
      .then(list =>
        this.setData({
          reports: (Array.isArray(list) ? list : []).map((item, index) => normalizeReport(item, index))
        })
      )
      .catch(() => wx.showToast({ title: '体质辨识记录加载失败，请稍后重试', icon: 'none' }))
      .then(() => this.setData({ loading: false }));
  },

  openReport(event) {
    const id = (event.currentTarget.dataset || {}).id;
    if (!id) return;
    wx.navigateTo({ url: `${REPORT_PAGE}?id=${id}` });
  }
});
