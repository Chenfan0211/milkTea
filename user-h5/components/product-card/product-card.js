Component({
  options: { styleIsolation: 'apply-shared' },
  properties: {
    product: { type: Object, value: {} },
    showDivider: { type: Boolean, value: false }
  },
  methods: {
    handleSpec() {
      this.triggerEvent('spec', { product: this.properties.product })
    }
  }
})
