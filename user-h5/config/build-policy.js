module.exports = {
  version: 'dev',
  features: {
    tongueHealth: true,
    memberZone: false
  },
  tabBar: [
    {
      pagePath: '/pages/home/home',
      text: '首页',
      icon: '/assets/icons/lucide/home.svg',
      activeIcon: '/assets/icons/lucide/home-active.svg'
    },
    {
      pagePath: '/pages/menu/menu',
      text: '点单',
      icon: '/assets/icons/lucide/menu.svg',
      activeIcon: '/assets/icons/lucide/menu-active.svg'
    },
    {
      pagePath: '/pages/tongue/tongue',
      text: '一人一饮',
      icon: '/assets/icons/lucide/personalized-drink.svg',
      activeIcon: '/assets/icons/lucide/personalized-drink-active.svg',
      feature: 'tongueHealth'
    },
    {
      pagePath: '/pages/orders/orders',
      text: '订单',
      icon: '/assets/icons/lucide/orders.svg',
      activeIcon: '/assets/icons/lucide/orders-active.svg'
    },
    {
      pagePath: '/pages/profile/profile',
      text: '我的',
      icon: '/assets/icons/lucide/profile.svg',
      activeIcon: '/assets/icons/lucide/profile-active.svg'
    }
  ]
};
