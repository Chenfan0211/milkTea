const productImage = '/assets/images/3x/menu-product.jpg';
function roundMoney(value) {
  return Math.round(value * 10) / 10;
}

const badgeMemberIcon = '/assets/icons/lucide/member-gold.svg';

function createSpecGroups() {
  return [
    {
      id: 'size',
      label: '份量',
      options: [{ id: 'medium', label: '中杯', selected: true, priceDelta: 0 }]
    },
    {
      id: 'temperature',
      label: '温度',
      options: [
        { id: 'standard-ice', label: '标准冰', selected: true, priceDelta: 0 },
        { id: 'less-ice', label: '少冰', selected: false, priceDelta: 0 },
        { id: 'no-ice', label: '去冰（微凉）', selected: false, priceDelta: 0 }
      ]
    },
    {
      id: 'topping',
      label: '加料（必选1份）',
      options: [
        { id: 'none', label: '不加马蹄粉圆', selected: true, priceDelta: 0 },
        { id: 'horseshoe', label: '加马蹄粉圆', selected: false, priceDelta: 2 }
      ]
    }
  ];
}

function createProduct(id, name, price = 13.9, originalPrice = 16, badgeIcon = '') {
  const tags = ['年度热销', '五窨茉莉花茶'];
  const description = '草本清香与醇厚茶韵交融，入口清甜顺滑，回甘自然。';
  return {
    id,
    name,
    tags,
    description,
    price,
    originalPrice,
    storedValuePrice: roundMoney(price - 1),
    badgeIcon,
    image: productImage,
    specDetail: {
      galleryImage: productImage,
      imageDisclaimer: '图片与杯型仅供参考，具体请以实物为准',
      promotionText: '周三会员日招牌饮品85折',
      priceLabel: '小程序价',
      startPrice: price,
      originalPrice,
      storedValuePrice: roundMoney(price - 1),
      discountRate: 0.85,
      tag: tags[1],
      description,
      ingredients: '一级千目抹茶+芒果鲜果+牛乳芝士+HPP冷冻芒果汁',
      allergens: '饮品内含有乳制品、芒果、无花果碎，过敏者请谨慎选择',
      cupCapacity: '杯型容量中杯500ml，标注容量及图片仅供参考，饮品量请以实际出品为准',
      tips: [
        '抹茶含有天然咖啡因，若您对咖啡因敏感，处于孕期、哺乳期或晚间饮用，建议您酌情选择。',
        '本产品含有无花果碎，少数人群可能对无花果或相关果类过敏。',
        '时令芒果品种会更换使用，请以门店实际出品为准。',
        '避免阳光直射、高温或急冻。',
        '建议2小时内饮用，开盖直饮风味更佳。'
      ],
      specGroups: createSpecGroups()
    }
  };
}

function createAppleMilkTea() {
  const product = createProduct('classic-005', '红苹果乌龙冰奶', 14.9, 16);
  const description =
    '浓郁苹果果香糅合岩香乌龙，丝滑冰博客融合绵柔牛乳芝士，果香茶香奶香三重交织，酸甜均衡，醇厚不腻。';
  return {
    ...product,
    tags: ['年度热销', '红苹果乌龙'],
    description,
    specDetail: {
      ...product.specDetail,
      discountRate: 1,
      originalBasePrice: 16,
      tag: '红苹果乌龙',
      description,
      ingredients: '冷冻苹果杏沙棘汁+马头岩乌龙茶+牛乳芝士+双倍蛋白冰博客牛奶',
      allergens: '饮品内含有乳制品，过敏者请谨慎选择',
      cupCapacity: '杯型容量中杯500ml，标注容量及图片仅供参考，饮品量请以实际出品为准',
      tips: [
        '果酸遇乳类蛋白会产生轻微絮状分层。',
        '此为正常现象，搅匀即可饮用。',
        '建议2小时内饮用，开盖直饮风味更佳。'
      ],
      specGroups: [
        {
          id: 'size',
          label: '份量',
          options: [{ id: 'medium', label: '中杯', selected: true, priceDelta: 0 }]
        },
        {
          id: 'temperature',
          label: '温度',
          options: [
            { id: 'standard-ice', label: '标准冰', selected: true, priceDelta: 0, icon: 'star' },
            { id: 'less-ice', label: '少冰', selected: false, priceDelta: 0 },
            { id: 'no-ice', label: '去冰（微凉）', selected: false, priceDelta: 0 }
          ]
        }
      ]
    }
  };
}

const classicGroups = [
  {
    id: 'recommend',
    label: '店长推荐',
    categories: [
      {
        id: 'herbal',
        label: '草本养生茶',
        products: [
          createProduct('classic-001', '五窨茉莉抹茶', 13.9, 16, badgeMemberIcon),
          createProduct('classic-002', '金桂轻乳茶'),
          createProduct('classic-003', '青提茉莉冰茶', 15.9, 18),
          createProduct('classic-004', '陈皮普洱轻乳茶', 14.9, 17),
          createAppleMilkTea(),
          createProduct('herbal-001', '桂香暖润茶', 12.9, 15),
          createProduct('herbal-002', '陈皮山楂茶', 13.9, 16),
          createProduct('herbal-003', '黑枸杞玫瑰茶', 15.9, 18, badgeMemberIcon)
        ]
      }
    ]
  },
  {
    id: 'leaf',
    label: '原叶臻选',
    categories: [
      {
        id: 'traditional',
        label: '传统原叶茶',
        products: [
          createProduct('leaf-001', '茉莉银毫', 14.9, 17),
          createProduct('leaf-002', '高山绿茶', 12.9, 15),
          createProduct('leaf-003', '鸭屎香单丛', 16.9, 19),
          createProduct('traditional-001', '冷萃龙井', 15.9, 18),
          createProduct('traditional-002', '白桃乌龙', 15.9, 18)
        ]
      }
    ]
  }
];

const featuredGroups = [
  {
    id: 'featured-signature',
    label: '招牌热销',
    categories: [
      {
        id: 'featured-season',
        label: '季节限定',
        products: [
          createProduct('featured-001', '五窨茉莉抹茶', 13.9, 16, badgeMemberIcon),
          createProduct('featured-002', '抹茶轻乳茶', 15.9, 18),
          createProduct('featured-003', '金秋桂花茶', 14.9, 17),
          createProduct('season-001', '青提抹茶冰萃', 16.9, 19),
          createProduct('season-002', '草莓抹茶奶绿', 17.9, 20)
        ]
      }
    ]
  }
];

const menuTabs = [
  { id: 'classic', label: '经典菜单', groups: classicGroups },
  { id: 'featured', label: '招牌主打', groups: featuredGroups }
];


module.exports = { menuTabs };
