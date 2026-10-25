const featurePolicy = require('../utils/feature-policy');

Component({
  data: {
    selected: 0,
    hidden: false,
    list: featurePolicy.getTabBarItems()
  },
  pageLifetimes: {
    show() {
      this.syncFeaturePolicy();
    }
  },
  methods: {
    syncFeaturePolicy() {
      const list = featurePolicy.getTabBarItems();
      if (JSON.stringify(list) !== JSON.stringify(this.data.list)) this.setData({ list });
    }
  }
});
