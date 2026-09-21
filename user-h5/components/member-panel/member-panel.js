Component({
  properties: {
    levels: { type: Array, value: [] },
    axis: { type: Array, value: [] },
    currentIndex: { type: Number, value: 0 },
    currentLevel: { type: String, value: '' },
    progressPercent: { type: Number, value: 0 },
    progressLabel: { type: String, value: '' },
    growth: { type: Number, value: 0 }
  },
  data: {
    activeIndex: 0,
    activeReached: true,
    activeName: '',
    activeDiscount: '',
    privilegesTitle: '',
    privileges: [],
    axisFillPercent: 0,
    axisInsetPercent: 16.67
  },
  observers: {
    'levels, currentIndex, axis': function onDataChange(levels, currentIndex, axis) {
      this.syncActive(currentIndex);
      this.syncAxis(axis);
    }
  },
  lifetimes: {
    attached() {
      this.syncActive(this.data.currentIndex);
      this.syncAxis(this.data.axis);
    }
  },
  methods: {
    syncActive(index) {
      const levels = this.data.levels || [];
      if (!levels.length) return;
      const safeIndex = Math.min(Math.max(Number(index) || 0, 0), levels.length - 1);
      const active = levels[safeIndex] || {};
      this.setData({
        activeIndex: safeIndex,
        activeReached: active.isReached !== false,
        activeName: active.name || '',
        activeDiscount: active.discount || '',
        privilegesTitle: (active.name || '') + ' 会员特权',
        privileges: (active.benefits || []).map(item => Object.assign({}, item))
      });
    },
    syncAxis(axis) {
      const nodes = axis || [];
      if (nodes.length < 2) {
        this.setData({ axisFillPercent: 0, axisInsetPercent: 50 });
        return;
      }
      const reachedCount = nodes.filter(node => node.reached).length;
      const ratio = (Math.max(reachedCount, 1) - 1) / (nodes.length - 1);
      this.setData({
        axisFillPercent: Math.round(ratio * 100),
        axisInsetPercent: Math.round((50 / nodes.length) * 100) / 100
      });
    },
    selectCard(event) {
      const index = Number(event.currentTarget.dataset.index);
      if (Number.isNaN(index) || index === this.data.activeIndex) return;
      this.syncActive(index);
      this.triggerEvent('selectlevel', { index });
    },
    openRules() {
      this.triggerEvent('openrules');
    }
  }
});
