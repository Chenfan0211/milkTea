const { calcMemberPrice } = require('../../utils/pricing');

Component({
  options: { styleIsolation: 'apply-shared' },
  properties: {
    product: { type: Object, value: {} },
    showDivider: { type: Boolean, value: false }
  },
  data: {
    displayPrice: ''
  },
  observers: {
    product(product) {
      if (!product || !product.name) return;
      const listPrice = Number(product.originalPrice || product.price || 0);
      this.setData({
        displayPrice: calcMemberPrice(listPrice)
      });
    }
  },
  methods: {
    handleSpec() {
      this.triggerEvent('spec', { product: this.properties.product });
    }
  }
});
