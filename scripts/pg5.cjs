const fs = require('fs');

// 1) MenuDTO.MenuProduct 补 badgeIcon
let d = fs.readFileSync('product-service/src/main/java/com/wuling/product/dto/MenuDTO.java', 'utf8');
if (!d.includes('badgeIcon')) {
  d = d.replace(
    "        /** 详情页标签文案 */\n        private String specTag;",
    "        /** 详情页标签文案 */\n        private String specTag;\n        /** 商品角标图标 */\n        private String badgeIcon;"
  );
  fs.writeFileSync('product-service/src/main/java/com/wuling/product/dto/MenuDTO.java', d, 'utf8');
  console.log('added badgeIcon to MenuDTO');
}

// 2) ProductDetailDTO 补 badgeIcon
let pd = fs.readFileSync('product-service/src/main/java/com/wuling/product/dto/ProductDetailDTO.java', 'utf8');
if (!pd.includes('badgeIcon')) {
  pd = pd.replace(
    "    /** 详情页标签文案 */\n    private String specTag;",
    "    /** 详情页标签文案 */\n    private String specTag;\n    /** 商品角标图标 */\n    private String badgeIcon;"
  );
  fs.writeFileSync('product-service/src/main/java/com/wuling/product/dto/ProductDetailDTO.java', pd, 'utf8');
  console.log('added badgeIcon to ProductDetailDTO');
}

// 3) ProductQueryService 填充 badgeIcon
let sv = fs.readFileSync('product-service/src/main/java/com/wuling/product/service/ProductQueryService.java', 'utf8');
if (!sv.includes('setBadgeIcon')) {
  sv = sv.replace(
    "        dto.setSpecTag(product.getSpecTag());\r\n        dto.setIngredients(product.getIngredients());\r\n        dto.setAllergens(product.getAllergens());\r\n        dto.setCupCapacity(product.getCupCapacity());\r\n        dto.setTips(parseStringList(product.getTips()));\r\n        dto.setSpecGroups(buildSpecGroups(specs));\r\n        return dto;\r\n    }\r\n\r\n    public PageResult<AdminProductDTO> pageAdminProducts",
    "        dto.setSpecTag(product.getSpecTag());\r\n        dto.setBadgeIcon(product.getBadgeIcon());\r\n        dto.setIngredients(product.getIngredients());\r\n        dto.setAllergens(product.getAllergens());\r\n        dto.setCupCapacity(product.getCupCapacity());\r\n        dto.setTips(parseStringList(product.getTips()));\r\n        dto.setSpecGroups(buildSpecGroups(specs));\r\n        return dto;\r\n    }\r\n\r\n    public PageResult<AdminProductDTO> pageAdminProducts"
  );
  sv = sv.replace(
    "        dto.setSpecTag(product.getSpecTag());\r\n        dto.setIngredients(product.getIngredients());\r\n        dto.setAllergens(product.getAllergens());\r\n        dto.setCupCapacity(product.getCupCapacity());\r\n        dto.setTips(parseStringList(product.getTips()));\r\n        dto.setSpecGroups(buildSpecGroups(specs));\r\n        return dto;\r\n    }\r\n\r\n    private List<MenuDTO.SpecGroup> buildSpecGroups",
    "        dto.setSpecTag(product.getSpecTag());\r\n        dto.setBadgeIcon(product.getBadgeIcon());\r\n        dto.setIngredients(product.getIngredients());\r\n        dto.setAllergens(product.getAllergens());\r\n        dto.setCupCapacity(product.getCupCapacity());\r\n        dto.setTips(parseStringList(product.getTips()));\r\n        dto.setSpecGroups(buildSpecGroups(specs));\r\n        return dto;\r\n    }\r\n\r\n    private List<MenuDTO.SpecGroup> buildSpecGroups"
  );
  fs.writeFileSync('product-service/src/main/java/com/wuling/product/service/ProductQueryService.java', sv, 'utf8');
  console.log('setBadgeIcon occurrences:', (sv.match(/setBadgeIcon/g) || []).length);
}
