const { withShare } = require('../../utils/share');
const { getCurrentBusinessRole, getCurrentSubjectId } = require('../../utils/roles');
const { getInvestStats, getSpots, listApplications, refreshInvestCatalog, refreshInvestApplications } = require('../../utils/invest');

const ROLE_CENTER_URL = '/packageRole/role-center/role-center';
const APPLY_URL = '/packageRole/role-invest-apply/role-invest-apply';
const RECORDS_URL = '/packageRole/role-invest-records/role-invest-records';

const SPOT_STATUS_TEXT = {
  available: '可申请',
  pending: '审核中',
  signed: '已签约',
  occupied: '已绑定',
  disabled: '已停用'
};

const FLOW_STEPS = [
  { id: 'pick', index: 1, label: '选择点位' },
  { id: 'submit', index: 2, label: '提交申请' },
  { id: 'audit', index: 3, label: '运营审核' },
  { id: 'sign', index: 4, label: '签约入驻' }
];

// 搜索匹配点位名称与地址。
function matchKeyword(spot, keyword) {
  const key = String(keyword || '').trim().toLowerCase();
  if (!key) return true;
  const name = String(spot.name || '').toLowerCase();
  const address = String(spot.address || '').toLowerCase();
  return name.indexOf(key) >= 0 || address.indexOf(key) >= 0;
}

Page(
  withShare({
    data: {
      ready: false,
      title: '点位投资申请',
      keyword: '',
      cityTabs: [{ id: 'all', label: '全部', count: 0 }],
      activeCityId: 'all',
      spots: [],
      filteredSpots: [],
      stats: { total: 0, available: 0, signed: 0, pending: 0, rejected: 0 },
      flowSteps: FLOW_STEPS,
      recordsSummary: '暂无申请记录'
    },
    onLoad() {
      this.syncRole();
    },
    onShow() {
      if (this.data.ready) this.syncSpots();
    },
    syncRole() {
      const role = getCurrentBusinessRole();
      if (!role || role.id !== 'investor') {
        this.setData({ ready: false });
        wx.showToast({ title: '仅投资人角色可申请点位投资', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      // 门店目录 + 申请记录均来自后端，先拉取镜像再渲染
      Promise.all([refreshInvestCatalog(), refreshInvestApplications()]).then(() => {
        this.setData({ ready: true }, () => this.syncSpots());
      });
    },
    // 展示所有启用门店；可申请 / 审核中 / 已签约 / 已绑定 / 已停用分别标记。
    syncSpots() {
      const spots = getSpots(getCurrentSubjectId()).map(spot =>
        Object.assign({}, spot, { statusLabel: SPOT_STATUS_TEXT[spot.spotStatus] || spot.spotStatus })
      );
      const applications = listApplications();
      const pendingCount = applications.filter(item => item.status === 'pending').length;
      this.setData(
        {
          spots,
          stats: getInvestStats(getCurrentSubjectId()),
          recordsSummary: applications.length
            ? `共 ${applications.length} 条${pendingCount ? `，${pendingCount} 条审核中` : ''}`
            : '暂无申请记录'
        },
        () => this.applyFilter()
      );
    },
    // 城市维度筛选，角标数量随关键词实时重算。
    applyFilter() {
      const { keyword, activeCityId } = this.data;
      const matched = (this.data.spots || []).filter(spot => matchKeyword(spot, keyword));
      const seen = [];
      matched.forEach(spot => {
        if (seen.indexOf(spot.cityCode) === -1) seen.push(spot.cityCode);
      });
      const cityTabs = [{ id: 'all', label: '全部', count: matched.length }].concat(
        seen.map(code => {
          const target = matched.find(spot => spot.cityCode === code);
          return {
            id: code,
            label: target.cityName,
            count: matched.filter(spot => spot.cityCode === code).length
          };
        })
      );
      const filteredSpots =
        activeCityId === 'all' ? matched : matched.filter(spot => spot.cityCode === activeCityId);
      this.setData({ cityTabs, filteredSpots });
    },
    handleKeyword(event) {
      this.setData({ keyword: event.detail.value }, () => this.applyFilter());
    },
    clearKeyword() {
      this.setData({ keyword: '' }, () => this.applyFilter());
    },
    switchCity(event) {
      this.setData({ activeCityId: event.currentTarget.dataset.id }, () => this.applyFilter());
    },
    openSpot(event) {
      const id = event.currentTarget.dataset.id;
      if (!id) return;
      const spot = this.data.spots.find(item => item.id === id);
      if (!spot) return;
      if (spot.spotStatus === 'disabled') {
        wx.showToast({ title: '该点位已停用，暂不可申请', icon: 'none' });
        return;
      }
      if (spot.spotStatus === 'signed') {
        wx.showToast({ title: '该点位已签约', icon: 'none' });
        return;
      }
      if (spot.spotStatus === 'occupied') {
        wx.showToast({ title: '该点位已绑定其他投资人', icon: 'none' });
        return;
      }
      if (spot.spotStatus === 'pending') {
        wx.navigateTo({ url: `${RECORDS_URL}` });
        return;
      }
      wx.navigateTo({ url: `${APPLY_URL}?storeId=${id}` });
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
    }
  })
);