const { withShare } = require('../../utils/share');
const { formatDateTime } = require('../../utils/date-format');
const {
  getCurrentBusinessRole,
  getIncomeData,
  syncIncomeFromRemote,
  INCOME_STATUS_TEXT
} = require('../../utils/roles');

const ROLE_CENTER_URL = '/pages/role-center/role-center';
const DETAIL_URL = '/pages/role-income-detail/role-income-detail';
const CATEGORIES = [
  { id: 'all', label: '全部' },
  { id: 'pending', label: '待结算' },
  { id: 'settled', label: '已结算' },
  { id: 'reversed', label: '已冲正' }
];

const DATE_PLACEHOLDER = { start: '开始时间', end: '结束时间' };

function pad(value) {
  return String(value).padStart(2, '0');
}

function toDateKey(year, month, day) {
  return `${year}-${pad(month)}-${pad(day)}`;
}

function recordDateKey(record) {
  return String((record && record.time) || '').slice(0, 10);
}

function daysInMonth(year, month) {
  return new Date(year, month, 0).getDate();
}

function buildRange(count, from) {
  return Array.from({ length: count }, (_, index) => from + index);
}

// 搜索只匹配收益单号，时间交由独立的开始/结束选择器处理。
function matchKeyword(record, keyword) {
  const key = String(keyword || '').trim().toLowerCase();
  if (!key) return true;
  return String(record.orderNo || '').toLowerCase().indexOf(key) >= 0;
}

// 闭区间比较，字符串按 YYYY-MM-DD 定长可直接字典序比较。
function matchDateRange(record, start, end) {
  if (!start && !end) return true;
  const dateKey = recordDateKey(record);
  if (!dateKey) return false;
  if (start && dateKey < start) return false;
  if (end && dateKey > end) return false;
  return true;
}

// 收益方向：冲正为支出，其余为入账。
function directionOf(record) {
  return record && record.status === 'reversed' ? 'out' : 'in';
}

Page(
  withShare({
    data: {
      ready: false,
      title: '收益记录',
      keyword: '',
      dateStart: '',
      dateEnd: '',
      dateStartLabel: DATE_PLACEHOLDER.start,
      dateEndLabel: DATE_PLACEHOLDER.end,
      hasFilter: false,
      categories: CATEGORIES.map(item => Object.assign({}, item, { count: 0 })),
      activeCategory: 'all',
      records: [],
      filteredRecords: [],
      datePickerVisible: false,
      datePickerTitle: '选择开始时间',
      pickerValue: [0, 0, 0],
      pickerYears: [],
      pickerMonths: [],
      pickerDays: []
    },
    onLoad() {
      const now = new Date();
      this.todayKey = toDateKey(now.getFullYear(), now.getMonth() + 1, now.getDate());
      this.syncRole();
    },
    onShow() {
      if (!this.data.ready) return;
      // 收益台账来自后端；拉到后重渲染，失败时保留现有列表
      syncIncomeFromRemote().then(() => this.syncRole());
    },
    syncRole() {
      const role = getCurrentBusinessRole();
      if (!role) {
        this.setData({ ready: false, title: '收益记录' });
        wx.showToast({ title: '请先开通并选择经营角色', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      const data = getIncomeData(role.id);
      if (!data) {
        this.setData({ ready: false, title: '收益记录' });
        wx.showToast({ title: '当前角色暂无收益数据', icon: 'none' });
        this.leaveToRoleCenter();
        return;
      }
      const records = this.decorate(data.records || []);
      this.setData({ ready: true, title: `${role.label}收益记录`, records }, () => this.applyFilter());
    },
    syncRecords() {
      const records = this.decorate(this.data.records);
      this.setData({ records }, () => this.applyFilter());
    },
    decorate(records) {
      return (records || []).map(item =>
        Object.assign({}, item, {
          statusLabel: INCOME_STATUS_TEXT[item.status] || item.status || '',
          direction: directionOf(item),
          timeText: formatDateTime(item.time)
        })
      );
    },
    applyFilter() {
      const { keyword, dateStart, dateEnd, activeCategory } = this.data;
      const matched = (this.data.records || []).filter(
        item => matchKeyword(item, keyword) && matchDateRange(item, dateStart, dateEnd)
      );
      const categories = CATEGORIES.map(item => ({
        id: item.id,
        label: item.label,
        count: item.id === 'all' ? matched.length : matched.filter(r => r.status === item.id).length
      }));
      const filteredRecords =
        activeCategory === 'all' ? matched : matched.filter(item => item.status === activeCategory);
      this.setData({
        categories,
        filteredRecords,
        hasFilter: Boolean(keyword || dateStart || dateEnd)
      });
    },
    handleKeyword(event) {
      this.setData({ keyword: event.detail.value }, () => this.applyFilter());
    },
    clearKeyword() {
      this.setData({ keyword: '' }, () => this.applyFilter());
    },
    switchCategory(event) {
      this.setData({ activeCategory: event.currentTarget.dataset.id }, () => this.applyFilter());
    },
    openDatePicker(event) {
      const field = event.currentTarget.dataset.field;
      const current = field === 'start' ? this.data.dateStart : this.data.dateEnd;
      const base = current || this.todayKey;
      const [year, month, day] = base.split('-').map(Number);
      this.pickerField = field;
      this.setData(
        {
          datePickerVisible: true,
          datePickerTitle: field === 'start' ? '选择开始时间' : '选择结束时间',
          pickerYears: buildRange(6, year - 4),
          pickerMonths: buildRange(12, 1),
          pickerDays: buildRange(daysInMonth(year, month), 1),
          pickerValue: [4, month - 1, day - 1]
        },
        () => this.syncPickerDays()
      );
    },
    closeDatePicker() {
      this.setData({ datePickerVisible: false });
    },
    handleDateChange(event) {
      const [yearIndex, monthIndex] = event.detail.value;
      const year = this.data.pickerYears[yearIndex];
      const month = this.data.pickerMonths[monthIndex];
      this.setData({ pickerValue: event.detail.value }, () => {
        const total = daysInMonth(year, month);
        if (this.data.pickerDays.length !== total) {
          this.setData({ pickerDays: buildRange(total, 1) });
        }
      });
    },
    syncPickerDays() {
      const [yearIndex, monthIndex] = this.data.pickerValue;
      const total = daysInMonth(this.data.pickerYears[yearIndex], this.data.pickerMonths[monthIndex]);
      if (this.data.pickerDays.length !== total) {
        this.setData({ pickerDays: buildRange(total, 1) });
      }
    },
    confirmDate() {
      const [yearIndex, monthIndex, dayIndex] = this.data.pickerValue;
      const year = this.data.pickerYears[yearIndex];
      const month = this.data.pickerMonths[monthIndex];
      const day = Math.min(this.data.pickerDays[dayIndex], daysInMonth(year, month));
      const picked = toDateKey(year, month, day);
      const next = { datePickerVisible: false };
      if (this.pickerField === 'start') {
        next.dateStart = picked;
        next.dateStartLabel = picked;
        if (this.data.dateEnd && this.data.dateEnd < picked) {
          next.dateEnd = picked;
          next.dateEndLabel = picked;
        }
      } else {
        next.dateEnd = picked;
        next.dateEndLabel = picked;
        if (this.data.dateStart && this.data.dateStart > picked) {
          next.dateStart = picked;
          next.dateStartLabel = picked;
        }
      }
      this.setData(next, () => this.applyFilter());
    },
    resetDates() {
      this.setData(
        {
          dateStart: '',
          dateEnd: '',
          dateStartLabel: DATE_PLACEHOLDER.start,
          dateEndLabel: DATE_PLACEHOLDER.end
        },
        () => this.applyFilter()
      );
    },
    copyOrderNo(event) {
      const orderNo = event.currentTarget.dataset.orderNo;
      if (!orderNo) return;
      wx.setClipboardData({
        data: orderNo,
        success: () => wx.showToast({ title: '单号已复制', icon: 'none' }),
        fail: () => wx.showToast({ title: '复制失败，请重试', icon: 'none' })
      });
    },
    openDetail(event) {
      const id = event.currentTarget.dataset.id;
      if (!id) return;
      wx.navigateTo({ url: `${DETAIL_URL}?id=${id}` });
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