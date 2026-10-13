const { withShare } = require('../../utils/share');
const { ensureBusinessRole } = require('../../utils/role-page');
const { getCurrentBusinessRole, getCurrentSubjectId } = require('../../utils/roles');
const {
  getInvestStats,
  getSpots,
  listApplications,
  refreshInvestCatalog,
  refreshInvestApplications
} = require('../../utils/invest');
const { buildStoreMarkers } = require('../../utils/store-markers');

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

/**
 * 点位状态筛选标签（顺序与 SPOT_STATUS_TEXT 一致）。
 * 「全部」在前，其余按状态枚举；数量为 0 的标签置灰但仍可点（切过去是空列表）。
 */
const STATUS_TABS = [
  { id: 'all', label: '全部' },
  { id: 'available', label: '可申请' },
  { id: 'pending', label: '审核中' },
  { id: 'signed', label: '已签约' },
  { id: 'occupied', label: '已绑定' },
  { id: 'disabled', label: '已停用' }
];

/** 定位不可用时的地图兜底中心（长沙）。 */
const DEFAULT_MAP_CENTER = { latitude: 28.2282, longitude: 112.9388 };

// 搜索匹配点位名称与地址。
function matchKeyword(spot, keyword) {
  const key = String(keyword || '')
    .trim()
    .toLowerCase();
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
      statusTabs: STATUS_TABS.map(tab => ({ id: tab.id, label: tab.label, count: 0 })),
      activeStatusId: 'all',
      // 城市维度：按后端 cityCode 拆分展示，避免跨城点位（长沙↔广州）同屏挤成一点。
      cityTabs: [],
      activeCityId: 'all',
      spots: [],
      citySpots: [],
      filteredSpots: [],
      // 视图模式：list=列表（默认）/ map=腾讯地图
      mode: 'list',
      markers: [],
      mapCenter: DEFAULT_MAP_CENTER,
      activeSpotId: null,
      geolessCount: 0,
      stats: { total: 0, available: 0, signed: 0, pending: 0, rejected: 0 },
      flowSteps: FLOW_STEPS,
      recordsSummary: '暂无申请记录'
    },
    onLoad() {
      // 先确保角色已同步（冷启动/直接进入时本地 Storage 尚无角色），
      // 再走原有 syncRole（内部自会按「有无角色」分流处理）。
      ensureBusinessRole().then(() => this.syncRole());
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
    /**
     * 按状态维度筛选：标签数字 = 当前关键词命中的该状态点位数。
     *
     * 历史实现按「城市」分组（cityTabs），标签只有数字没有状态语义，
     * 用户看不到「数字后面是什么状态」；现改为状态标签，与卡片徽标同一口径。
     */
    /**
     * 城市标签：按后端 cityCode 聚合当前全部点位，供「按城市拆分显示」使用。
     * 返回 [{ id, label, count }]，id 为 cityCode（'all' 表示全部城市）。
     */
    buildCityTabs(spots) {
      const seen = [];
      const map = {};
      (spots || []).forEach(spot => {
        const code = spot.cityCode || '';
        if (!code) return;
        if (!map[code]) {
          map[code] = { id: code, label: spot.cityName || code, count: 0 };
          seen.push(map[code]);
        }
        map[code].count += 1;
      });
      // 单城市时无需城市切换，避免多一行无意义标签
      if (seen.length <= 1) return [];
      return [{ id: 'all', label: '全部城市', count: (spots || []).length }].concat(seen);
    },
    /**
     * 两级筛选：城市 -> 状态。
     *
     * 城市维度（activeCityId）先把跨城点位拆开（长沙 4 个 / 广州 2 个），
     * 状态标签的数量口径基于「当前城市内、关键词命中的点位」，与该城市下的实际卡片一致。
     * 历史实现只有状态维度，跨城点位会同时出现在地图上并被缩放到不可辨识。
     */
    applyFilter() {
      const { keyword, activeStatusId, activeCityId } = this.data;
      const cityTabs = this.buildCityTabs(this.data.spots);
      const matchedKeyword = (this.data.spots || []).filter(spot => matchKeyword(spot, keyword));
      // 城市过滤：'all' 或城市码已不存在（点位变动）时回落到全量，避免切到空城市卡死
      const cityExists = cityTabs.some(tab => tab.id === activeCityId);
      const effectiveCityId = activeCityId === 'all' || !cityExists ? 'all' : activeCityId;
      const citySpots =
        effectiveCityId === 'all'
          ? matchedKeyword
          : matchedKeyword.filter(spot => (spot.cityCode || '') === effectiveCityId);
      const statusTabs = STATUS_TABS.map(tab => ({
        id: tab.id,
        label: tab.label,
        count: tab.id === 'all' ? citySpots.length : citySpots.filter(spot => spot.spotStatus === tab.id).length
      }));
      const filteredSpots =
        activeStatusId === 'all' ? citySpots : citySpots.filter(spot => spot.spotStatus === activeStatusId);
      this.setData({ cityTabs, activeCityId: effectiveCityId, citySpots, statusTabs, filteredSpots }, () =>
        this.syncMarkers()
      );
    },
    handleKeyword(event) {
      this.setData({ keyword: event.detail.value }, () => this.applyFilter());
    },
    clearKeyword() {
      this.setData({ keyword: '' }, () => this.applyFilter());
    },
    switchStatus(event) {
      this.setData({ activeStatusId: event.currentTarget.dataset.id }, () => this.applyFilter());
    },
    /** 城市切换：按 cityCode 拆分点位，切换后重新框定地图视野 */
    switchCity(event) {
      const id = event.currentTarget.dataset.id;
      if (!id || id === this.data.activeCityId) return;
      this.setData({ activeCityId: id, activeStatusId: 'all' }, () => this.applyFilter());
    },
    /**
     * 列表 / 地图模式切换。
     *
     * 进入地图时「优先框住所有点位」：跨城点位在默认 scale 下会被挤出视野，
     * 表现为「地图上没有点位」。定位仅用于「当前筛选结果无坐标点位」时的兜底中心。
     */
    switchMode(event) {
      const mode = event.currentTarget.dataset.mode;
      if (!mode || mode === this.data.mode) return;
      this.setData({ mode }, () => {
        if (mode === 'map') {
          this.syncMarkers();
          this.includeAllPoints();
        }
      });
    },
    /**
     * 用当前筛选结果生成地图标记。
     *
     * 复用 store-markers 的 buildStoreMarkers（图标/激活态/callout 与「门店地图」一致），
     * 仅传入有坐标的点位，避免 (0,0) 把标记打到大西洋。
     */
    syncMarkers() {
      const spots = (this.data.filteredSpots || []).filter(spot => spot.hasGeo);
      const markers = buildStoreMarkers(spots, this.data.activeSpotId);
      this.setData({ markers, geolessCount: (this.data.filteredSpots || []).length - spots.length });
    },
    /**
     * 把当前筛选出的点位全部纳入地图视野（方案 A 的核心）。
     *
     * 用 MapContext.includePoints 自动计算缩放级别与中心，保证：
     *   · 单城市多点位 -> 刚好铺满视野；
     *   · 跨城（长沙↔广州）-> 缩到能同时看到，不会被定位挤到视野外。
     * 无坐标点位或缺少 createMapContext 时，回落到「用户定位 / 首个点位 / 默认坐标」。
     */
    includeAllPoints() {
      const points = (this.data.filteredSpots || [])
        .filter(spot => spot.hasGeo)
        .map(spot => ({ latitude: spot.latitude, longitude: spot.longitude }));
      if (!points.length) {
        // 没有可打点数据：沿用定位兜底，至少让地图有合理中心
        this.locateUser();
        return;
      }
      // 单点：includePoints 会把视野缩到最小，直接居中该点更自然
      if (points.length === 1) {
        this.setData({ mapCenter: points[0] });
        return;
      }
      if (typeof wx.createMapContext !== 'function') {
        this.setData({ mapCenter: points[0] });
        return;
      }
      const ctx = wx.createMapContext('investMap', this);
      if (!ctx || typeof ctx.includePoints !== 'function') {
        this.setData({ mapCenter: points[0] });
        return;
      }
      // padding 单位 px：上左下右，避免 marker 贴边或被 callout 遮挡
      ctx.includePoints({ points, padding: [90, 50, 90, 50] });
    },
    /**
     * 地图默认居中「用户当前定位」。
     *
     * 兜底链：定位成功 -> 用定位坐标；拒绝授权/失败 -> 首个有坐标点位 -> 内置默认坐标。
     * 任一步都不阻断页面（地图仍可正常缩放拖动）。
     */
    locateUser() {
      if (typeof wx.getLocation !== 'function') {
        this.applyFallbackAnchor();
        return;
      }
      wx.getLocation({
        type: 'gcj02',
        success: res => {
          this.setData({ mapCenter: { latitude: res.latitude, longitude: res.longitude } });
        },
        fail: () => {
          this.applyFallbackAnchor();
        }
      });
    },
    applyFallbackAnchor() {
      const first = (this.data.filteredSpots || []).find(spot => spot.hasGeo);
      const center = first ? { latitude: first.latitude, longitude: first.longitude } : DEFAULT_MAP_CENTER;
      this.setData({ mapCenter: center });
    },
    /** 点击地图标记：与列表点击同一套分流逻辑 */
    handleMarkerTap(event) {
      const markerId = event && event.detail && event.detail.markerId;
      const marker = (this.data.markers || []).find(item => item.id === markerId);
      if (!marker) return;
      const spot = this.data.spots.find(item => item.id === marker.storeId);
      if (!spot) return;
      this.setData({ activeSpotId: spot.id });
      this.openSpotById(spot.id);
    },
    handleKeyword(event) {
      this.setData({ keyword: event.detail.value }, () => this.applyFilter());
    },
    clearKeyword() {
      this.setData({ keyword: '' }, () => this.applyFilter());
    },
    /** 列表点击：取 dataset.id 后走统一分流 */
    openSpot(event) {
      this.openSpotById(event.currentTarget.dataset.id);
    },
    /**
     * 点位点击分流（列表与地图标记共用，避免两处逻辑漂移）。
     * 不可申请的状态给明确提示；审核中跳申请记录；可申请才进申请表。
     */
    openSpotById(id) {
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
