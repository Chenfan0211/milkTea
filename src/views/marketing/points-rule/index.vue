<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue';
import { useAdminStore } from '@/store/modules/admin';
import { fetchSigninRule } from '@/service/api/crud';

const store = useAdminStore();

// ===== 签到规则 =====
const dailyReward = ref(store.signInDaily);
const rewards = ref<Array<{ days: number; amount: number }>>([]);

// 挂载时从后端加载积分获取规则与签到规则
onMounted(async () => {
  await store.loadRemote('pointsEarningRules');
  // 行为字典：页面「行为」列只读展示的来源
  await store.loadRemote('dictEntries');
  try {
    const rule: any = await fetchSigninRule();
    if (rule) {
      dailyReward.value = rule.daily ?? dailyReward.value;
      rewards.value =
        Array.isArray(rule.rewards) && rule.rewards.length
          ? rule.rewards
          : [{ days: rule.streakDays ?? 7, amount: rule.streakReward ?? 20 }];
    }
  } catch (error: any) {
    // 不回退到本地默认值：避免界面显示与数据库不一致的规则却不易察觉
    window.$message?.error(error?.message || '签到规则加载失败');
  }
});

watch(
  () => store.signInRewards,
  val => {
    rewards.value = JSON.parse(JSON.stringify(val || []));
  },
  { immediate: true, deep: true }
);

function addReward() {
  rewards.value.push({ days: 7, amount: 20 });
}

function removeReward(index: number) {
  rewards.value.splice(index, 1);
}

async function saveSignRule() {
  await store.saveSignInRule({
    daily: Number(dailyReward.value),
    rewards: rewards.value.map(r => ({ days: Number(r.days), amount: Number(r.amount) }))
  });
  window.$message?.success('签到规则已保存');
}

// ===== 积分获取规则 =====
//
// 设计口径（2026-09-28）：
//   · 「行为」来自数据字典 points_action，本页只读、不可增删；
//   · 「奖励」拆成结构化数字存库（reward_type/value + basis_amount/unit + daily_limit），
//     供后续按规则发放时光币直接计算，不必解析文本；
//   · 本页只允许修改奖励信息与说明。
const REWARD_TYPES = [
  { label: '按消费比例（每 X 元送 Y 币）', value: 'per-yuan' },
  { label: '固定值（每次送 Y 币）', value: 'fixed' },
  { label: '按单位固定值（每人/次送 Y 币）', value: 'fixed-per' },
  { label: '倍数（Y 倍时光币）', value: 'multiplier' }
] as const;

const BASIS_UNITS = [
  { label: '元', value: 'yuan' },
  { label: '人', value: 'person' },
  { label: '次', value: 'time' },
  { label: '天', value: 'day' }
] as const;

/** 行为字典（points_action）：key=code，value=展示名 */
const actionOptions = computed(() =>
  (store.dictEntries || [])
    .filter((e: any) => e.dictType === 'points_action' && Number(e.enabled) === 1)
    .map((e: any) => ({
      label: String(e.itemName ?? '').trim(),
      value: String(e.itemCode ?? '').trim(),
      sort: Number(e.sort) || 0,
      id: Number(e.id) || 0
    }))
    .filter((o: any) => o.value)
    .sort((a: any, b: any) => a.sort - b.sort || a.id - b.id)
);

/** code -> 展示名（表格「行为」列只读展示用） */
const actionLabelOf = (code: string) => {
  const hit = actionOptions.value.find((o: any) => o.value === code);
  return hit?.label || code || '';
};

/** 奖励文案（按结构化数字生成，避免手写文案与数字不一致） */
function rewardTextOf(row: any): string {
  const v = row?.rewardValue;
  if (v == null) return row?.reward || '';
  switch (row?.rewardType) {
    case 'per-yuan': {
      const basisYuan = (Number(row?.basisAmount) || 0) / 100;
      return `每消费${basisYuan}元 + ${v}币`;
    }
    case 'fixed':
      return `+${v}币`;
    case 'fixed-per': {
      const unit = BASIS_UNITS.find(u => u.value === row?.basisUnit)?.label || '';
      return `+${v}币/${unit}`;
    }
    case 'multiplier':
      return `${v}倍时光币`;
    default:
      return row?.reward || '';
  }
}

const ruleModalVisible = ref(false);
const ruleMode = ref<'add' | 'edit'>('edit');
const editingRule = ref<any>(null);
const ruleForm = reactive({
  code: '',
  action: '',
  note: '',
  rewardType: 'fixed' as string,
  rewardValue: 1 as number | null,
  basisAmountYuan: 1 as number | null,
  basisUnit: 'yuan' as string,
  dailyLimit: null as number | null
});

/** 是否按消费比例：只有它需要「每 X 元」基准 */
const isPerYuan = computed(() => ruleForm.rewardType === 'per-yuan');

function openRuleModal(row: any) {
  ruleMode.value = 'edit';
  editingRule.value = row || null;
  ruleForm.code = row?.code || '';
  ruleForm.action = row?.action || '';
  ruleForm.note = row?.note || '';
  ruleForm.rewardType = row?.rewardType || 'fixed';
  ruleForm.rewardValue = row?.rewardValue == null ? 1 : Number(row.rewardValue);
  ruleForm.basisAmountYuan = row?.basisAmount == null ? 1 : Number(row.basisAmount) / 100;
  ruleForm.basisUnit = row?.basisUnit || 'yuan';
  ruleForm.dailyLimit = row?.dailyLimit == null ? null : Number(row.dailyLimit);
  ruleModalVisible.value = true;
}

async function submitRule() {
  if (!editingRule.value) return;
  if (ruleForm.rewardValue == null || Number(ruleForm.rewardValue) <= 0) {
    window.$message?.warning('奖励数值必须为正整数');
    return;
  }
  if (isPerYuan.value && (ruleForm.basisAmountYuan == null || Number(ruleForm.basisAmountYuan) <= 0)) {
    window.$message?.warning('按消费比例发放时必须填写「每 X 元」');
    return;
  }

  const payload: Record<string, unknown> = {
    // 行为与编码来自字典，只读，不随表单修改
    code: ruleForm.code,
    action: ruleForm.action,
    note: ruleForm.note,
    rewardType: ruleForm.rewardType,
    rewardValue: Number(ruleForm.rewardValue),
    // 金额基准统一按「分」存库
    basisAmount: isPerYuan.value ? Math.round(Number(ruleForm.basisAmountYuan) * 100) : null,
    basisUnit: ruleForm.basisUnit,
    dailyLimit: ruleForm.dailyLimit == null ? null : Number(ruleForm.dailyLimit)
  };
  // 展示文案随数字自动生成，避免两者不一致
  payload.reward = rewardTextOf({
    rewardType: payload.rewardType,
    rewardValue: payload.rewardValue,
    basisAmount: payload.basisAmount,
    basisUnit: payload.basisUnit
  });

  await store.update('pointsEarningRules', editingRule.value.id, payload, '营销中心', 'action');
  ruleModalVisible.value = false;
  window.$message?.success('奖励已保存');
}

/** 切换启用/停用：只有启用的规则才对小程序端生效并展示。 */
async function toggleEnabled(row: any) {
  const next = row.enabled === 1 || row.enabled === true ? 0 : 1;
  await store.update('pointsEarningRules', row.id, { enabled: next }, '营销中心', 'action');
  window.$message?.success(next === 1 ? '已启用' : '已停用');
}

const ruleColumns = [
  {
    title: '行为',
    key: 'action',
    width: 220,
    render: (row: any) => actionLabelOf(row.action)
  },
  { title: '奖励', key: 'reward', width: 180, render: (row: any) => rewardTextOf(row) },
  {
    title: '每日上限',
    key: 'dailyLimit',
    width: 100,
    render: (row: any) => (row.dailyLimit == null ? '不限' : `${row.dailyLimit} 次`)
  },
  {
    title: '状态',
    key: 'enabled',
    width: 90,
    render: (row: any) => {
      const enabled = row.enabled === 1 || row.enabled === true;
      return h(
        'span',
        { class: enabled ? 'rule-status rule-status--on' : 'rule-status rule-status--off' },
        enabled ? '启用' : '停用'
      );
    }
  },
  { title: '说明', key: 'note', minWidth: 200 },
  {
    title: '操作',
    key: '__actions__',
    width: 90,
    render: (row: any) => {
      const enabled = row.enabled === 1 || row.enabled === true;
      return h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap' }, [
        h('button', { class: 'rule-btn rule-btn--edit', onClick: () => openRuleModal(row) }, '编辑奖励'),
        h(
          'button',
          { class: enabled ? 'rule-btn rule-btn--off' : 'rule-btn rule-btn--on', onClick: () => toggleEnabled(row) },
          enabled ? '停用' : '启用'
        )
      ]);
    }
  }
];
</script>

<template>
  <div class="points-rule-page">
    <NCard :bordered="false" title="签到规则" class="mb-16px">
      <NForm label-placement="left" :label-width="140" class="sign-form">
        <NFormItem label="每日签到奖励">
          <NInputNumber v-model:value="dailyReward" :min="0" class="w-160px" />
          <span class="ml-8px">时光币</span>
        </NFormItem>
        <NFormItem label="连续签到奖励">
          <div class="rewards-editor">
            <div v-for="(r, index) in rewards" :key="index" class="reward-row">
              <span>连续</span>
              <NInputNumber v-model:value="r.days" :min="1" class="w-100px" />
              <span>天奖励</span>
              <NInputNumber v-model:value="r.amount" :min="0" class="w-100px" />
              <span>时光币</span>
              <NButton size="tiny" quaternary type="error" @click="removeReward(index)">删除</NButton>
            </div>
            <NButton size="small" dashed type="primary" @click="addReward">+ 添加奖励档位</NButton>
          </div>
        </NFormItem>
        <NFormItem>
          <NButton type="primary" @click="saveSignRule">保存签到规则</NButton>
        </NFormItem>
      </NForm>
    </NCard>

    <NCard :bordered="false" title="积分获取规则">
      <template #header-extra>
        <span class="rule-hint">行为来自数据字典 points_action，本页只可修改奖励</span>
      </template>
      <NDataTable
        :columns="ruleColumns"
        :data="store.pointsEarningRules"
        :bordered="false"
        :row-key="(row: any) => row.id"
        :scroll-x="1000"
      />
    </NCard>

    <NModal v-model:show="ruleModalVisible" preset="card" title="编辑奖励" class="w-480px">
      <NForm label-placement="left" :label-width="96">
        <!-- 行为：来自字典 points_action，只读，不允许在本页修改 -->
        <NFormItem label="行为">
          <NInput :value="actionLabelOf(ruleForm.code) || ruleForm.action" readonly />
        </NFormItem>

        <NFormItem label="奖励类型">
          <NSelect v-model:value="ruleForm.rewardType" :options="REWARD_TYPES as any" />
        </NFormItem>

        <NFormItem v-if="isPerYuan" label="每 X 元">
          <NInputNumber v-model:value="ruleForm.basisAmountYuan" :min="0.01" :precision="2" class="w-160px" />
          <span class="ml-8px">元</span>
        </NFormItem>

        <NFormItem :label="ruleForm.rewardType === 'multiplier' ? '倍数' : '奖励'">
          <NInputNumber v-model:value="ruleForm.rewardValue" :min="1" :precision="0" class="w-160px" />
          <span class="ml-8px">{{ ruleForm.rewardType === 'multiplier' ? '倍' : '时光币' }}</span>
        </NFormItem>

        <NFormItem v-if="ruleForm.rewardType === 'fixed-per'" label="计量单位">
          <NSelect v-model:value="ruleForm.basisUnit" :options="BASIS_UNITS as any" />
        </NFormItem>

        <NFormItem label="每日上限">
          <NInputNumber
            v-model:value="ruleForm.dailyLimit"
            :min="0"
            :precision="0"
            class="w-160px"
            placeholder="留空表示不限"
          />
        </NFormItem>

        <NFormItem label="说明"><NInput v-model:value="ruleForm.note" /></NFormItem>
      </NForm>
      <div class="flex flex-wrap justify-end gap-12px">
        <NButton @click="ruleModalVisible = false">取消</NButton>
        <NButton type="primary" @click="submitRule">确定</NButton>
      </div>
    </NModal>
  </div>
</template>

<style scoped>
.points-rule-page {
  max-width: 1000px;
}
.sign-form {
  max-width: 600px;
}
.rewards-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.reward-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.rule-btn {
  padding: 2px 10px;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}
.rule-status {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 4px;
  font-size: 12px;
}
.rule-status--on {
  background: #e8f5e9;
  color: #53882c;
}
.rule-status--off {
  background: #f5f5f5;
  color: #8b8f86;
}
.rule-btn--on {
  background: #e8f5e9;
  color: #53882c;
}
.rule-btn--off {
  background: #fdecea;
  color: #e65a5a;
}
.rule-btn--edit {
  background: #e8f5e9;
  color: #53882c;
}
.rule-hint {
  color: #8b8f86;
  font-size: 12px;
}
.rule-btn--del {
  background: #fdecea;
  color: #e65a5a;
}
</style>
