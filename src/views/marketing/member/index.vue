<script setup lang="ts">
defineOptions({
  name: 'MarketingMember'
});

import { computed, reactive, ref } from 'vue';
import AdminListPage from '@/views/_shared/AdminListPage.vue';
import type { AdminListConfig, SearchField, RowAction } from '@/views/_shared/types';
import type { DataTableColumns } from 'naive-ui';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();
const listRef = ref<InstanceType<typeof AdminListPage> | null>(null);

interface BenefitRow {
  icon: string;
  text: string;
  /** 仅「专属优惠券」使用：绑定的优惠券模板 ID */
  couponId: number | null;
  /** 仅「专属优惠券」使用：数量（正整数） */
  count: number | null;
}

/**
 * 权益文案 -> 图标（与小程序端 assets/icons/lucide 同名）。
 *
 * 图标与文案绑定：图标不再由用户手选，而是按文案自动带出，
 * 避免「基础折扣」配「皇冠」这类语义错乱的组合。
 *
 * 注意：后台在字典 member_benefit 新增权益文案时，必须同步在此补一行映射，
 * 否则该行图标会为空（界面会给出提示）。
 */
const BENEFIT_ICON_BY_TEXT: Record<string, string> = {
  基础折扣: 'badge-percent',
  生日月双倍时光币: 'calendar-check',
  专属会员价: 'badge-japanese-yen',
  专属优惠券: 'ticket-percent',
  新品优先体验: 'star',
  '时光币1.5倍': 'trending-up',
  生日免费饮品: 'gift'
};

/** 专属优惠券的文案标识（前端提交文案；后端同时兼容 item_code）。 */
const MEMBER_COUPON_TEXT = '专属优惠券';

/** 按文案取图标；未配置映射时回退空串，由界面提示补映射。 */
function iconForBenefit(text: string): string {
  return BENEFIT_ICON_BY_TEXT[String(text ?? '').trim()] ?? '';
}

/** 是否为「专属优惠券」权益（只有它需要关联优惠券与数量）。 */
function isCouponBenefit(text: string): boolean {
  return String(text ?? '').trim() === MEMBER_COUPON_TEXT;
}

/**
 * 图标名 -> 可访问 URL。
 *
 * 复用与 BenefitIconSelect 相同的资源来源（src/assets/lucide/，由
 * scripts/sync-benefit-icons.mjs 从 user-h5 同步）。必须从本工程内引用：
 * 跨到 user-h5（独立工程）时 Vite 的 ?url 无法生成可访问 URL。
 */
const benefitIconModules = import.meta.glob('/src/assets/lucide/*.svg', {
  eager: true,
  query: '?url',
  import: 'default'
}) as Record<string, string>;

const benefitIconUrlMap = Object.fromEntries(
  Object.entries(benefitIconModules).map(([path, url]) => [path.split('/').pop()?.replace(/.svg$/, '') ?? '', url])
) as Record<string, string>;

/** 按图标名取 URL；未找到时返回空串（界面显示占位）。 */
function benefitIconUrl(name: string): string {
  return benefitIconUrlMap[String(name ?? '').trim()] ?? '';
}

const editorVisible = ref(false);
const editorMode = ref<'add' | 'edit'>('add');
const editingId = ref<number | null>(null);
const editorTitle = computed(() => (editorMode.value === 'add' ? '新增会员等级' : '编辑会员等级'));
const benefits = ref<BenefitRow[]>([]);
const originalDiscount = ref('');
const discountWasInvalid = ref(false);

const form = reactive({
  levelCode: '',
  name: '',
  amountTarget: null as number | null,
  discount: null as number | null
});

function toBenefitRow(raw: any): BenefitRow {
  if (typeof raw === 'string') {
    return { icon: iconForBenefit(raw), text: raw, couponId: null, count: null };
  }
  const text = String(raw?.text ?? '');
  const rawCouponId = raw?.couponId;
  const couponId = rawCouponId == null || rawCouponId === '' ? null : Number(rawCouponId);
  return {
    // 图标以文案映射为准；历史数据里的 icon 仅在映射缺失时兜底
    icon: iconForBenefit(text) || String(raw?.icon ?? ''),
    text,
    couponId: Number.isFinite(couponId as number) ? couponId : null,
    count: raw?.count == null || raw?.count === '' ? null : Number(raw.count)
  };
}

/** 兼容数组、JSON 字符串、普通字符串和旧 string[] 权益结构。 */
function parseBenefits(raw: any): BenefitRow[] {
  if (Array.isArray(raw)) return raw.map(toBenefitRow);
  if (typeof raw === 'string' && raw.trim()) {
    try {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) return parsed.map(toBenefitRow);
    } catch {
      return [{ icon: iconForBenefit(raw), text: raw.trim(), couponId: null, count: null }];
    }
  }
  return [];
}

/** 历史折扣 / 新百分比统一转换为后台整数百分比输入值。 */
function normalizeDiscountPercent(raw: any): number | null {
  if (raw == null) return null;
  const text = String(raw).trim();
  if (!text) return null;

  const zheMatch = text.match(/([-+]?\d+(?:\.\d+)?)\s*折/);
  if (zheMatch) {
    const zhe = Number(zheMatch[1]);
    return zhe > 0 && zhe <= 10 ? Math.round(zhe * 10) : null;
  }

  const percentMatch = text.match(/([-+]?\d+(?:\.\d+)?)\s*%/);
  if (percentMatch) {
    const percent = Number(percentMatch[1]);
    return percent > 0 && percent <= 100 ? Math.round(percent) : null;
  }

  const value = Number(text);
  if (!Number.isFinite(value) || value <= 0) return null;
  if (value > 0 && value < 1) return Math.round(value * 100);
  if (Number.isInteger(value) && value <= 100) return value;
  return null;
}

const benefitTextOptions = computed(() => {
  const options = store.dictEntries
    .filter((entry: any) => entry.dictType === 'member_benefit' && Number(entry.enabled) === 1)
    .map((entry: any) => ({
      label: String(entry.itemName ?? '').trim(),
      value: String(entry.itemName ?? '').trim(),
      sort: Number(entry.sort) || 0,
      id: Number(entry.id) || 0
    }))
    .filter((entry: any) => entry.value)
    .sort((a: any, b: any) => a.sort - b.sort || a.id - b.id);

  const seen = new Set<string>();
  return options
    .filter((entry: any) => {
      if (seen.has(entry.value)) return false;
      seen.add(entry.value);
      return true;
    })
    .map(({ label, value }: any) => ({ label, value }));
});

function benefitOptionsFor(text: string) {
  const current = String(text ?? '').trim();
  const options = benefitTextOptions.value;
  if (current && !options.some(option => option.value === current)) {
    return [{ label: current, value: current }, ...options];
  }
  return options;
}

/**
 * 可绑定的优惠券列表（供「专属优惠券」权益选择）。
 *
 * 只列出启用中的券；已停用的券不应再被新的等级绑定。
 */
const couponOptions = computed(() =>
  (store.coupons || [])
    .filter((coupon: any) => coupon && String(coupon.status ?? 'enabled') !== 'disabled')
    .map((coupon: any) => ({
      label: String(coupon.name ?? coupon.code ?? coupon.id),
      value: Number(coupon.id)
    }))
    .filter((option: any) => Number.isFinite(option.value))
);

function resetEditor() {
  form.levelCode = '';
  form.name = '';
  form.amountTarget = null;
  form.discount = null;
  benefits.value = [{ icon: '', text: '', couponId: null, count: null }];
  editingId.value = null;
  originalDiscount.value = '';
  discountWasInvalid.value = false;
}

function openEditor(mode: 'add' | 'edit', row?: any) {
  resetEditor();
  editorMode.value = mode;
  if (mode === 'edit' && row) {
    editingId.value = Number(row.id);
    form.levelCode = String(row.levelCode ?? '');
    form.name = String(row.name ?? '');
    form.amountTarget = row.amountTarget == null ? null : Number(row.amountTarget) / 100;
    originalDiscount.value = String(row.discount ?? '');
    const normalizedDiscount = normalizeDiscountPercent(row.discount);
    form.discount = normalizedDiscount;
    discountWasInvalid.value = normalizedDiscount == null && Boolean(originalDiscount.value.trim());
    const parsed = parseBenefits(row.benefits);
    benefits.value = parsed.length ? parsed : [{ icon: '', text: '', couponId: null, count: null }];
  }
  editorVisible.value = true;
}

function addBenefitRow() {
  benefits.value.push({ icon: '', text: '', couponId: null, count: null });
}

function removeBenefitRow(index: number) {
  benefits.value.splice(index, 1);
}

function buildBenefits() {
  return benefits.value
    .filter(row => String(row.text ?? '').trim())
    .map(row => {
      const text = String(row.text ?? '').trim();
      const item: Record<string, unknown> = {
        // 图标始终由文案推导，保证与文案语义一致
        icon: iconForBenefit(text) || String(row.icon ?? '').trim(),
        text
      };
      // 只有专属优惠券才写入优惠券与数量，其他权益不落这两个字段
      if (isCouponBenefit(text)) {
        if (row.couponId != null) item.couponId = Number(row.couponId);
        item.count = row.count == null ? '' : String(row.count).trim();
      }
      return item;
    });
}

async function saveEditor() {
  const levelCode = form.levelCode.trim();
  const name = form.name.trim();
  if (!levelCode) {
    window.$message?.warning('请输入等级编码');
    return;
  }
  if (!name) {
    window.$message?.warning('请输入等级名称');
    return;
  }
  if (form.discount != null && (!Number.isInteger(form.discount) || form.discount < 1 || form.discount > 100)) {
    window.$message?.warning('折扣请输入 1-100 的整数百分比');
    return;
  }

  // 权益校验：与后端 CrudService#guardMemberLevel 保持一致，
  // 前端先拦一遍是为了给出可读提示，避免用户提交后才看到接口报错。
  const rows = benefits.value.filter(row => String(row.text ?? '').trim());
  for (const row of rows) {
    const text = String(row.text ?? '').trim();
    if (isCouponBenefit(text)) {
      if (row.couponId == null) {
        window.$message?.warning('专属优惠券必须绑定优惠券');
        return;
      }
      const count = row.count == null ? NaN : Number(row.count);
      if (!Number.isInteger(count) || count <= 0) {
        window.$message?.warning('专属优惠券必须填写数量（正整数）');
        return;
      }
    } else if (row.count != null && String(row.count).trim()) {
      // 其他权益不允许带数量，避免与后端规则冲突
      window.$message?.warning('只有专属优惠券才需要填写数量');
      return;
    }
  }

  let discount = '';
  if (form.discount == null) {
    discount = editingId.value && discountWasInvalid.value ? originalDiscount.value : '';
  } else {
    discount = String(form.discount);
  }

  const amountTarget = Math.round((Number(form.amountTarget) || 0) * 100);
  const payload = {
    levelCode,
    name,
    amountTarget,
    discount,
    benefits: buildBenefits()
  };

  try {
    if (editingId.value) {
      await store.update('memberLevels', editingId.value, payload, '营销中心', 'name');
    } else {
      await store.add('memberLevels', payload, '营销中心', 'name');
    }
    editorVisible.value = false;
    window.$message?.success(editingId.value ? '会员等级已更新' : '会员等级已新增');
    await listRef.value?.reload();
  } catch (error: any) {
    window.$message?.error(error?.message || '保存失败');
  }
}

/** 分 -> 元展示（amount_target 单位为分） */
function fenToYuan(fen: any): string {
  const n = Number(fen);
  if (!Number.isFinite(n)) return '0.00';
  return (n / 100).toFixed(2);
}

const columns: DataTableColumns<any> = [
  { title: '等级', key: 'levelCode', width: 100 },
  { title: '名称', key: 'name', width: 140 },
  {
    title: '门槛(元)',
    key: 'amountTarget',
    width: 110,
    align: 'right',
    render: (row: any) => fenToYuan(row.amountTarget)
  },
  {
    title: '权益',
    key: 'benefits',
    minWidth: 260,
    render: (row: any) =>
      parseBenefits(row.benefits)
        .map(item => item.text)
        .filter(Boolean)
        .join('、') || '—'
  }
];

const searchFields: SearchField[] = [{ key: 'name', label: '名称', placeholder: '等级名称' }];
const toolbar: RowAction[] = [{ label: '新增等级', type: 'primary', handler: () => openEditor('add') }];
const rowActions: RowAction[] = [
  { label: '编辑', type: 'primary', handler: (row: any) => openEditor('edit', row) },
  {
    label: '删除',
    type: 'error',
    reasonPrompt: '确认删除该等级？（请填写备注）',
    handler: async (row, reason) => await store.remove('memberLevels', row.id, '营销中心', 'name', reason)
  }
];

const config: AdminListConfig = {
  title: '会员等级',
  remoteDeps: ['dictEntries'],
  columns,
  searchFields,
  toolbar,
  rowActions,
  loadData: async ({ page, pageSize, search }) => store.queryRemote('memberLevels', search, page, pageSize)
};
</script>

<template>
  <div class="page-root">
    <AdminListPage ref="listRef" :config="config" />

    <NModal v-model:show="editorVisible" preset="card" :title="editorTitle" class="w-860px">
      <NForm label-placement="left" :label-width="96">
        <NFormItem label="等级">
          <NInput
            v-model:value="form.levelCode"
            :disabled="editorMode === 'edit'"
            placeholder="如 Lv1（编辑时不可修改）"
          />
        </NFormItem>

        <NFormItem label="名称">
          <NInput v-model:value="form.name" placeholder="如 时光卡" />
        </NFormItem>

        <NFormItem label="门槛(元)">
          <NInputNumber
            v-model:value="form.amountTarget"
            :min="0"
            :precision="2"
            placeholder="按元填写，保存时自动换算为分"
            class="w-240px"
          />
        </NFormItem>

        <NFormItem label="折扣">
          <div class="discount-input">
            <NInputNumber
              v-model:value="form.discount"
              :min="1"
              :max="100"
              :precision="0"
              :show-button="false"
              placeholder="如 80"
              class="w-160px"
            />
            <span class="discount-input__suffix">%</span>
            <span class="discount-input__hint">80 表示支付原价的 80%</span>
          </div>
        </NFormItem>

        <NFormItem label="权益">
          <div class="benefit-editor">
            <div class="benefit-editor__head">
              <span>图标</span>
              <span>权益文案</span>
              <span class="benefit-editor__scope-head">优惠券 / 数量</span>
              <span>操作</span>
            </div>

            <div v-for="(benefit, index) in benefits" :key="index" class="benefit-editor__row">
              <!-- 图标由文案自动带出，只读展示，避免与文案语义错乱 -->
              <span class="benefit-editor__icon" :title="iconForBenefit(benefit.text) || '未配置图标'">
                <img
                  v-if="iconForBenefit(benefit.text)"
                  :src="benefitIconUrl(iconForBenefit(benefit.text))"
                  alt=""
                  draggable="false"
                />
                <span v-else class="benefit-editor__icon-empty">—</span>
              </span>
              <NSelect
                v-model:value="benefit.text"
                :options="benefitOptionsFor(benefit.text)"
                placeholder="选择权益文案"
                filterable
                clearable
              />
              <!-- 只有「专属优惠券」才需要关联优惠券并填写数量 -->
              <div v-if="isCouponBenefit(benefit.text)" class="benefit-editor__coupon">
                <NSelect
                  v-model:value="benefit.couponId"
                  :options="couponOptions"
                  placeholder="选择优惠券"
                  filterable
                  clearable
                />
                <NInputNumber v-model:value="benefit.count" :min="1" :precision="0" placeholder="数量" />
              </div>
              <span v-else class="benefit-editor__empty-cell">—</span>
              <NButton size="small" type="error" quaternary @click="removeBenefitRow(index)">删除</NButton>
            </div>

            <div v-if="!benefits.length" class="benefit-editor__empty">暂无权益，点击下方按钮添加</div>

            <div class="mt-8px">
              <NButton size="small" @click="addBenefitRow">新增一条权益</NButton>
            </div>
            <div class="benefit-editor__tip">
              权益文案来自数据字典 member_benefit，图标随文案自动匹配；仅「专属优惠券」需关联优惠券并填写数量。
            </div>
          </div>
        </NFormItem>

        <div class="flex flex-wrap justify-end gap-12px mt-20px">
          <NButton @click="editorVisible = false">取消</NButton>
          <NButton type="primary" @click="saveEditor">保存</NButton>
        </div>
      </NForm>
    </NModal>
  </div>
</template>

<style scoped>
.discount-input {
  display: flex;
  align-items: center;
  gap: 8px;
}

.discount-input__suffix {
  color: #555;
  font-weight: 600;
}

.discount-input__hint {
  color: #8b8f86;
  font-size: 13px;
}

.benefit-editor {
  width: 100%;
}

.benefit-editor__head,
.benefit-editor__row {
  display: grid;
  grid-template-columns: 56px minmax(200px, 1fr) 260px 64px;
  gap: 8px;
  align-items: center;
}

.benefit-editor__head {
  margin-bottom: 6px;
  color: #6f746b;
  font-size: 13px;
}

.benefit-editor__row {
  margin-bottom: 8px;
}

.benefit-editor__empty {
  padding: 12px;
  border: 1px dashed #dcdfd7;
  border-radius: 6px;
  color: #8b8f86;
  font-size: 13px;
  text-align: center;
}

.benefit-editor__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
}

.benefit-editor__icon img {
  width: 24px;
  height: 24px;
  object-fit: contain;
  display: block;
}

.benefit-editor__icon-empty {
  color: #c7cbc2;
  font-size: 14px;
}

/* 专属优惠券：优惠券选择 + 数量 */
.benefit-editor__coupon {
  display: grid;
  grid-template-columns: minmax(140px, 1fr) 96px;
  gap: 8px;
  align-items: center;
}

/* 非专属优惠券：该列留空，保持行高一致 */
.benefit-editor__empty-cell {
  color: #c7cbc2;
  font-size: 13px;
}

.benefit-editor__tip {
  margin-top: 8px;
  color: #8b8f86;
  font-size: 12px;
}
</style>
