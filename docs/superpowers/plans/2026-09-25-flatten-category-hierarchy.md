# 分类层级拍平（去层级 + 分类标签 + 商品必绑分类）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把运营后台「分类管理」从 TAB/GROUP/CATEGORY 三级树拍平成单层「分类」，每个分类自带「左上角标签」，商品必须绑定分类，上架后挂到该分类下。

**Architecture:** 数据层保留 `product_category` 单表（物理删除旧 TAB/GROUP 行、`type` 固定 `CATEGORY`），后端 `ProductQueryService#getMenu()` 从三级循环改为单层扁平，`MenuDTO.MenuCategory`/`CategoryDTO`/`ProductCategory` 补齐 `tag`/`enabled` 字段；前端分类页去掉层级下拉、商品页把 `categoryId` 设为必填；小程序菜单页去掉分组层、分类项左上角渲染 `tag` 角标。

**Tech Stack:** Java 17 / Spring Boot / MyBatis-Plus / MySQL（Flyway 迁移），Vue 3 + Naive UI（运营后台 `src/`），原生微信小程序（`user-h5/`）。

**Spec:** 本任务需求（用户确认的三项决策）：① 旧 TAB/GROUP 数据**物理删除**；② 菜单结构**完全单层**（无 Tab、无分组，仅一个分类列表）；③ 「标签」= 每个分类自己配置的 `tag`，即小程序端分类项**左上角**的小角标。

## Global Constraints

- 金额单位「分」（BIGINT），比例「万分比」（INT），逻辑删除 `deleted=1`。
- 数据库迁移文件统一放在 `server/src/main/resources/db/migration/`，新迁移命名为 `V38__<name>.sql`（当前最大版本号 V37）。
- 后端产品域代码在 `product-service/`，通用 CRUD 在 `server/`（`com.wuling.system.crud`）。
- 运营后台 UI 复用 `src/views/_shared/AdminListPage.vue` 与 `AdminListConfig` 类型，不另起组件。
- 小程序 UI 遵守 `user-h5/docs/design-system.md`，颜色/字号/圆角只取 token，单位 rpx，禁止 px、禁止写死近似色值。
- 新增/修改页面或组件后，须在 `user-h5` 执行 `npm run check` 与 `node user-h5/scripts/check-project.mjs`（见 AGENTS.md）。

---

### Task 1: 数据库迁移 — 物理删除旧层级、分类必绑

**Files:**
- Create: `server/src/main/resources/db/migration/V38__flatten_category_hierarchy.sql`

**Interfaces:**
- Consumes: 现有表结构 `product_category`（`id/parent_id/code/name/type/sort/tag/enabled`）、`product`（`category_id BIGINT UNSIGNED NULL`）。
- Produces: 迁移后 `product_category` 仅剩 `type='CATEGORY'` 的行，`product.category_id` 变为 `NOT NULL`。

- [ ] **Step 1: 编写迁移 SQL**

```sql
-- =============================================================
-- 分类层级拍平：去掉 TAB/GROUP/CATEGORY 三级，统一为单层「分类」
--
-- 决策（用户确认）：
--   1. 旧 TAB/GROUP 数据物理删除；
--   2. 菜单结构完全单层（无 Tab、无分组）；
--   3. 「标签」= 每个分类自身 tag（小程序端分类左上角角标）。
--
-- 影响面：
--   - product_category 仅保留 CATEGORY 行；TAB/GROUP 行物理删除。
--   - 分组级 tag 下沉到其下 CATEGORY 行（避免左上角标签信息丢失）。
--   - product.category_id 由 NULL 改为 NOT NULL，保证「商品必绑分类」。
-- =============================================================

-- 1) 分组级 tag 下沉到其直属分类（原分组 tag 有值时，分类 tag 为空则继承）
UPDATE product_category c
JOIN product_category g
  ON g.id = c.parent_id AND g.type = 'GROUP'
   SET c.tag = COALESCE(NULLIF(c.tag, ''), g.tag);

-- 2) 校验：是否存在 category_id 为 NULL 或指向非 CATEGORY 的商品（应手动处理，迁移脚本不做兜底赋值）
--    （人工核对：若存在，运营需先为这些商品补选分类后再执行迁移）
-- SELECT p.id, p.name FROM product p
--   LEFT JOIN product_category c ON c.id = p.category_id AND c.type = 'CATEGORY'
--  WHERE p.deleted = 0 AND (p.category_id IS NULL OR c.id IS NULL);

-- 3) 物理删除旧层级节点（TAB / GROUP）
DELETE FROM product_category WHERE type IN ('TAB', 'GROUP');

-- 4) 剩余分类统一 type = 'CATEGORY'（历史数据可能已有小写/空格，归一化）
UPDATE product_category SET type = 'CATEGORY' WHERE UPPER(TRIM(type)) = 'CATEGORY';

-- 5) 商品分类改必填
ALTER TABLE product
    MODIFY category_id BIGINT UNSIGNED NOT NULL COMMENT '所属分类 id（必填）';
```

- [ ] **Step 2: 核对迁移不破坏 seed 数据**

迁移前 seed 数据（`V4__seed_product.sql`）有 8 条分类：4 条 CATEGORY（id 3/5/8 + 另 1 条）、2 条 TAB（id 1/6）、2 条 GROUP（id 2/4/7）。确认迁移后仅剩 CATEGORY 行，且所有 `product.category_id` 指向现存 CATEGORY id（seed 中商品 category_id 均为 3 或 8，指向 CATEGORY，无 NULL、无悬空引用）。

- [ ] **Step 3: 提交**

```bash
git add server/src/main/resources/db/migration/V38__flatten_category_hierarchy.sql
git commit -m "feat(category): 迁移拍平分类层级并令商品必绑分类"
```

---

### Task 2: 后端 DTO/实体补齐 tag/enabled 字段

**Files:**
- Modify: `product-service/src/main/java/com/wuling/product/entity/ProductCategory.java`
- Modify: `product-service/src/main/java/com/wuling/product/dto/CategoryDTO.java`
- Modify: `product-service/src/main/java/com/wuling/product/dto/MenuDTO.java`

**Interfaces:**
- Consumes: 现有字段（`id/parentId/code/name/type/sort`）。
- Produces:
  - `ProductCategory` 新增 `private String tag;`、`private Integer enabled;`
  - `CategoryDTO` 新增 `private String tag;`、`private Integer enabled;`
  - `MenuDTO.MenuCategory` 新增 `private String tag;`（getter/setter 由 Lombok `@Data` 生成）

- [ ] **Step 1: 修改 `ProductCategory.java`**

在 `private Integer sort;` 之后、`private LocalDateTime createTime;` 之前插入：

```java
    /** 分类标签（小程序端分类左上角角标） */
    private String tag;
    /** 状态 1启用 0停用 */
    private Integer enabled;
```

- [ ] **Step 2: 修改 `CategoryDTO.java`**

在 `private Integer sort;` 之后插入：

```java
    private String tag;
    private Integer enabled;
```

- [ ] **Step 3: 修改 `MenuDTO.java` 的 `MenuCategory` 内部类**

在 `private String label;` 之后插入：

```java
        /** 分类左上角标签 */
        private String tag;
```

- [ ] **Step 4: 编译验证**

Run: `cd product-service && mvn -q -DskipTests compile`（或项目根目录统一构建命令）
Expected: BUILD SUCCESS，无编译错误。

- [ ] **Step 5: 提交**

```bash
git add product-service/src/main/java/com/wuling/product/entity/ProductCategory.java product-service/src/main/java/com/wuling/product/dto/CategoryDTO.java product-service/src/main/java/com/wuling/product/dto/MenuDTO.java
git commit -m "feat(category): 实体与 DTO 补齐 tag/enabled 字段"
```

---

### Task 3: 后端 getMenu 改为单层扁平

**Files:**
- Modify: `product-service/src/main/java/com/wuling/product/service/ProductQueryService.java`（`getMenu()` 方法，约 2501–5525 字节处）

**Interfaces:**
- Consumes: Task 2 的 `MenuDTO.MenuCategory#tag`、`ProductCategory#tag/enabled`。
- Produces: `getMenu()` 返回单层菜单：`List<MenuTab>` 只含 1 个 tab（`id="menu"`, `label="菜单"`），tab 下 1 个 group（`id="all"`, `label="全部"`），group 下直接是分类列表，每个分类带 `id/code`、`label/name`、`tag`、`products`。

- [ ] **Step 1: 重写 `getMenu()` 方法体**

用以下实现整体替换现有 `getMenu()`（保留方法签名 `public List<MenuDTO.MenuTab> getMenu()`）：

```java
    public List<MenuDTO.MenuTab> getMenu() {
        // 单层分类：仅启用且 type=CATEGORY 的节点参与菜单
        List<ProductCategory> categories = categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
                .eq(ProductCategory::getType, "CATEGORY")
                .eq(ProductCategory::getEnabled, 1)
                .orderByAsc(ProductCategory::getSort)
                .orderByAsc(ProductCategory::getId));
        List<Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getOnSale, 1)
                .orderByAsc(Product::getId));
        List<ProductSpec> specs = specMapper.selectList(new LambdaQueryWrapper<ProductSpec>()
                .orderByAsc(ProductSpec::getSort)
                .orderByAsc(ProductSpec::getId));

        Map<Long, List<ProductSpec>> specsByProduct = specs.stream()
                .collect(Collectors.groupingBy(ProductSpec::getProductId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, List<Product>> productsByCategory = products.stream()
                .collect(Collectors.groupingBy(Product::getCategoryId, LinkedHashMap::new, Collectors.toList()));

        // 完全单层：1 个 tab -> 1 个 group -> 分类列表
        MenuDTO.MenuTab tabDto = new MenuDTO.MenuTab();
        tabDto.setId("menu");
        tabDto.setLabel("菜单");

        MenuDTO.MenuGroup groupDto = new MenuDTO.MenuGroup();
        groupDto.setId("all");
        groupDto.setLabel("全部");

        List<MenuDTO.MenuCategory> categoryDtos = new ArrayList<>();
        for (ProductCategory category : categories) {
            MenuDTO.MenuCategory categoryDto = new MenuDTO.MenuCategory();
            categoryDto.setId(category.getCode());
            categoryDto.setLabel(category.getName());
            categoryDto.setTag(category.getTag());
            categoryDto.setProducts(productsByCategory.getOrDefault(category.getId(), List.of()).stream()
                    .map(p -> toMenuProduct(p, specsByProduct.getOrDefault(p.getId(), List.of())))
                    .toList());
            categoryDtos.add(categoryDto);
        }
        groupDto.setCategories(categoryDtos);

        List<MenuDTO.MenuGroup> groups = new ArrayList<>();
        groups.add(groupDto);
        tabDto.setGroups(groups);

        List<MenuDTO.MenuTab> tabs = new ArrayList<>();
        tabs.add(tabDto);
        return tabs;
    }
```

- [ ] **Step 2: 清理不再使用的 import 与变量**

删除原方法内 `Map<Long, ProductCategory> categoryById`（已不再使用）。确认 `Function` import 若已无其他引用则一并删除（先全局搜索 `Function` 在本文件的其它使用；`toAdminProduct`/`toMenuProduct` 中未用 `Function`，故删除 `import java.util.function.Function;`）。

- [ ] **Step 3: 同步修改 `listCategories()` 只返回分类层**

将 `listCategories()` 的查询加上过滤，并补齐 `tag/enabled` 映射：

```java
    public List<CategoryDTO> listCategories() {
        return categoryMapper.selectList(new LambdaQueryWrapper<ProductCategory>()
                        .eq(ProductCategory::getType, "CATEGORY")
                        .orderByAsc(ProductCategory::getSort)
                        .orderByAsc(ProductCategory::getId))
                .stream().map(category -> {
                    CategoryDTO dto = new CategoryDTO();
                    dto.setId(category.getId());
                    dto.setParentId(category.getParentId());
                    dto.setCode(category.getCode());
                    dto.setName(category.getName());
                    dto.setType(category.getType());
                    dto.setSort(category.getSort());
                    dto.setTag(category.getTag());
                    dto.setEnabled(category.getEnabled());
                    return dto;
                }).toList();
    }
```

- [ ] **Step 4: 编译验证**

Run: `cd product-service && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 提交**

```bash
git add product-service/src/main/java/com/wuling/product/service/ProductQueryService.java
git commit -m "feat(category): 菜单组装改为单层扁平并下发分类标签"
```

---

### Task 4: 后端商品写操作校验 categoryId 必填

**Files:**
- Modify: `product-service/src/main/java/com/wuling/product/service/AdminProductWriteService.java`

**Interfaces:**
- Consumes: 现有 `create(Map)` / `update(Long, Map)`、`applyEditable`、`number(...)`。
- Produces: 新增/编辑商品时 `categoryId` 缺失或非法（不存在/非 CATEGORY）即抛 `BusinessException(BAD_REQUEST, ...)`。

- [ ] **Step 1: 注入 `ProductCategoryMapper`**

在字段区新增：

```java
    private final ProductCategoryMapper productCategoryMapper;
```

在构造器参数与赋值处对应新增 `ProductCategoryMapper productCategoryMapper`（构造器当前为 4 参：`ProductMapper, ProductSpecMapper, ProductStoreMapper, ProductQueryService`，改为 5 参）。

- [ ] **Step 2: 新增校验私有方法**

```java
    /** 校验分类必填且为有效 CATEGORY 节点 */
    private void requireCategoryId(Long categoryId) {
        if (categoryId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "请选择商品分类");
        }
        ProductCategory category = productCategoryMapper.selectById(categoryId);
        if (category == null || !"CATEGORY".equals(category.getType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "商品分类无效");
        }
    }
```

- [ ] **Step 3: 在 `create` 与 `update` 中调用**

`create`：在 `applyEditable(product, payload);` 之后、`product.setOnSale(...)` 之前插入 `requireCategoryId(product.getCategoryId());`。
`update`：在 `applyEditable(product, payload);` 之后、`if (payload.containsKey("onSale"))` 之前插入 `requireCategoryId(product.getCategoryId());`。

- [ ] **Step 4: 导入实体与 Mapper**

确认文件顶部已 `import com.wuling.product.entity.Product;`，新增：

```java
import com.wuling.product.entity.ProductCategory;
import com.wuling.product.mapper.ProductCategoryMapper;
```

- [ ] **Step 5: 编译验证**

Run: `cd product-service && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS。

- [ ] **Step 6: 提交**

```bash
git add product-service/src/main/java/com/wuling/product/service/AdminProductWriteService.java
git commit -m "feat(category): 新增/编辑商品强制校验分类必填"
```

---

### Task 5: 运营后台分类管理页去掉层级

**Files:**
- Modify: `src/views/product/category/index.vue`

**Interfaces:**
- Consumes: `AdminListConfig` / `FormField` 类型、`useAdminStore`。
- Produces: 表单不再出现「层级类型」下拉，提交时 `type` 固定写 `'CATEGORY'`，`tag` 文案改为「左上角标签」。

- [ ] **Step 1: 删除 `TYPE_OPTIONS` 常量**

删除文件顶部：

```ts
/** 层级类型：与数据库 type 列取值一致 */
const TYPE_OPTIONS = [
  { label: '菜单页签（TAB）', value: 'TAB' },
  { label: '分组（GROUP）', value: 'GROUP' },
  { label: '分类（CATEGORY）', value: 'CATEGORY' }
];
```

- [ ] **Step 2: 调整 `tag` 表单项文案**

将 `formFields` 中：

```ts
{ key: 'tag', label: '分类标签', placeholder: '如：热销 / 新品（可留空）' },
```

改为：

```ts
{ key: 'tag', label: '左上角标签', placeholder: '小程序端分类左上角展示（可留空）' },
```

- [ ] **Step 3: 移除 `type` 表单项**

删除 `formFields` 中的 `type` 项：

```ts
  {
    key: 'type',
    label: '层级类型',
    type: 'select',
    options: TYPE_OPTIONS,
    placeholder: '请选择层级类型',
    rules: [{ required: true, message: '请选择层级类型', trigger: ['change', 'blur'] }]
  },
```

- [ ] **Step 4: `onSubmit` 固定写 `type`**

将 `onSubmit` 中的 payload 构造改为固定 `type: 'CATEGORY'`：

```ts
      const payload = {
        ...data,
        type: 'CATEGORY',
        tag: data.tag ? String(data.tag).trim() : '',
        enabled: Number(data.enabled) === 0 ? 0 : 1,
        sort: Number(data.sort) || 0
      };
```

- [ ] **Step 5: 更新顶部注释**

将文件顶部注释中「type 为 NOT NULL 列（TAB/GROUP/CATEGORY），表单必须提供」相关说明改为「type 固定为 CATEGORY，由前端写死；分类层级已拍平为单层」。

- [ ] **Step 6: 提交**

```bash
git add src/views/product/category/index.vue
git commit -m "feat(category): 分类管理页去掉层级，type 固定为 CATEGORY"
```

---

### Task 6: 运营后台商品管理页分类必填（已基本具备，仅优化提示文案）

**Files:**
- Modify: `src/views/product/list/index.vue`

**Interfaces:**
- Consumes: `categoryOptions()`（已过滤 CATEGORY）。
- Produces: 商品表单 `categoryId` 必填提示文案更明确。

**现状说明：** 商品表单 `formFields` 中 `categoryId` 字段**已带必填校验** `rules: [requiredRule]`，其中 `requiredRule = { required: true, message: '请填写', trigger: ['blur', 'change'] }`。功能上已满足「分类必填」，仅提示文案为通用的「请填写」，不够精准。

- [ ] **Step 1: 为 `categoryId` 提供专用必填文案**

将 `formFields` 中 `categoryId` 项的 `rules` 由 `[requiredRule]` 改为专用提示：

```ts
  {
    key: 'categoryId',
    label: '分类',
    type: 'select',
    options: () => categoryOptions(),
    rules: [{ required: true, message: '请选择商品分类', trigger: ['change', 'blur'] }]
  },
```

- [ ] **Step 2: 确认 `categoryOptions()` 无需改动**

`categoryOptions()` 已按 `type === 'CATEGORY'` 过滤；Task 3 后 `listCategories()` 已只返回 CATEGORY，逻辑仍兼容（保留原过滤作为双保险，不必改）。

- [ ] **Step 3: 提交**

```bash
git add src/views/product/list/index.vue
git commit -m "feat(category): 优化商品分类必填提示文案"
```

---

### Task 7: 小程序菜单页去掉分组、渲染分类左上角标签

**Files:**
- Modify: `user-h5/pages/menu/menu.wxml`
- Modify: `user-h5/pages/menu/menu.wxss`

**Interfaces:**
- Consumes: Task 3 后端下发的单层菜单（`activeMenu.groups[0].categories[]` 每个 `category` 带 `tag`）。
- Produces: 左侧分类栏直接渲染分类列表（无 group 分组标题），每个分类项左上角显示 `tag` 角标。

- [ ] **Step 1: 修改 `menu.wxml` 左侧分类栏**

将当前 `category-scroll` 内 `group -> categories` 双层循环，改为直接遍历 `activeMenu.groups[0].categories`（保留 group 外壳以兼容现有 `getFirstCategoryId`/`buildProductRows` 逻辑），并把原 `category-group__label`（分组标签头）改为分类项的左上角角标：

```xml
  <view class="menu-content">
    <scroll-view class="category-scroll" scroll-y enhanced show-scrollbar="{{false}}">
      <view
        wx:for="{{activeMenu.groups[0].categories}}"
        wx:for-item="category"
        wx:key="id"
        class="category-item {{selectedCategoryId === category.id ? 'is-active' : ''}}"
        data-id="{{category.id}}"
        aria-role="button"
        aria-label="{{category.label}}"
        bindtap="selectCategory"
      >
        <view wx:if="{{category.tag}}" class="category-item__tag">{{category.tag}}</view>
        <text class="category-item__label">{{category.label}}</text>
      </view>
    </scroll-view>
    <!-- product-scroll 部分保持不变 -->
  </view>
```

> 说明：`activeMenu.groups[0]` 在 `renderMenu` 里已保证存在（`selectedGroupId: activeMenu.groups[0].id`）。若担心空数组报错，可在 `wx:for` 前加 `wx:if="{{activeMenu.groups.length}}"` 保护。

- [ ] **Step 2: 修改 `menu.wxss` 分类项样式，新增左上角角标**

删除/停用 `.category-group__label` 相关样式（或保留无害），新增分类项角标样式（颜色取 design-system token）：

```css
.category-item {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 59rpx;
  padding: 0 24rpx;
  color: var(--brand-green);
  font-size: 24rpx;
  line-height: 34rpx;
}

.category-item__tag {
  position: absolute;
  top: 6rpx;
  left: 8rpx;
  padding: 0 6rpx;
  color: #ffffff;
  font-size: 16rpx;
  line-height: 24rpx;
  background: var(--brand-green);
  border-radius: 0 8rpx 8rpx 0;
}
```

> 若 design-system 对「角标/标签」有专用 token（背景色、字号档位），优先引用该 token，禁止写死近似色值；上例 `var(--brand-green)` 与 `#ffffff` 为既有变量/白字，与现 `category-group__label` 一致，可沿用。

- [ ] **Step 3: 运行检查脚本**

Run: `cd user-h5 && npm run check`
Expected: 无报错。

- [ ] **Step 4: 运行图标/资源一致性检查**

Run: `node user-h5/scripts/check-project.mjs`
Expected: 通过。

- [ ] **Step 5: 提交**

```bash
git add user-h5/pages/menu/menu.wxml user-h5/pages/menu/menu.wxss
git commit -m "feat(menu): 菜单改为单层分类并渲染分类左上角标签"
```

---

## Self-Review 记录

- **Spec 覆盖**：① 物理删除旧层级 → Task 1 Step 3；② 完全单层 → Task 3（后端）+ Task 7（小程序）；③ 标签=分类左上角信息 → Task 2/3（下发）+ Task 5（后台文案）+ Task 7（渲染）；④ 商品必绑分类 → Task 4（后端）+ Task 6（前台表单）+ Task 1 Step 5（DB NOT NULL）；⑤ 上架挂分类下 → 已有 `getMenu` 按 `on_sale=1` + `category_id` 分组，Task 3 保留该逻辑，无需额外改动。
- **Placeholder 扫描**：无 TBD/TODO/“类似 Task N”；每个代码步骤给出完整代码块。
- **类型一致性**：`tag` 字段在 `ProductCategory`、`CategoryDTO`、`MenuDTO.MenuCategory` 三处统一为 `String`；`enabled` 在 `ProductCategory`/`CategoryDTO` 为 `Integer`；`categoryId` 全链路 `Long`。
- **遗留确认点**（执行前需人工核对）：`V38` 迁移中 Step 2 的校验 SQL 结果需人工确认无悬空/空分类商品；`product-catalog.js` 中的硬编码 `PLATFORM_PRODUCTS` 为历史遗留，本次不改（不影响菜单接口主链路），后续可另行清理。

