<script setup lang="ts">
import { h, onMounted, reactive, ref, watch } from 'vue';
import { useAdminStore } from '@/store/modules/admin';
import { fetchSigninRule } from '@/service/api/crud';

const store = useAdminStore();

// 挂载时从后端加载积分获取规则与签到规则
onMounted(async () => {
  await store.loadRemote('pointsEarningRules');
  try {
    const rule: any = await fetchSigninRule();
    if (rule) {
      dailyReward.value = rule.daily ?? dailyReward.value;
      rewards.value = Array.isArray(rule.rewards) && rule.rewards.length
        ? rule.rewards
        : [{ days: rule.streakDays ?? 7, amount: rule.streakReward ?? 20 }];
    }
  } catch {
    // 拉取失败时沿用本地默认值
  }
});

// ===== 签到规则 =====
const dailyReward = ref(store.signInDaily);
const rewards = ref<Array<{ days: number; amount: number }>>([]);

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

function saveSignRule() {
  store.saveSignInRule({
    daily: Number(dailyReward.value),
    rewards: rewards.value.map(r => ({ days: Number(r.days), amount: Number(r.amount) }))
  });
  window.$message?.success('签到规则已保存');
}

// ===== 积分获取规则 =====
const ruleModalVisible = ref(false);
const ruleMode = ref<'add' | 'edit'>('add');
const editingRule = ref<any>(null);
const ruleForm = reactive({ action: '', reward: '', note: '' });

function openRuleModal(mode: 'add' | 'edit', row?: any) {
  ruleMode.value = mode;
  editingRule.value = row || null;
  ruleForm.action = row?.action || '';
  ruleForm.reward = row?.reward || '';
  ruleForm.note = row?.note || '';
  ruleModalVisible.value = true;
}

function submitRule() {
  if (ruleMode.value === 'edit' && editingRule.value) {
    store.update('pointsEarningRules', editingRule.value.id, { ...ruleForm }, '营销中心', 'action');
  } else {
    store.add('pointsEarningRules', { ...ruleForm }, '营销中心', 'action');
  }
  ruleModalVisible.value = false;
}

function removeRule(row: any) {
  store.remove('pointsEarningRules', row.id, '营销中心', 'action');
}

const ruleColumns = [
  { title: '行为', key: 'action', width: 200 },
  { title: '奖励', key: 'reward', width: 140 },
  { title: '说明', key: 'note', minWidth: 240 },
  {
    title: '操作',
    key: '__actions__',
    width: 140,
    render: (row: any) =>
      h('div', { style: 'display:flex;gap:8px;flex-wrap:wrap' }, [
        h('button', { class: 'rule-btn rule-btn--edit', onClick: () => openRuleModal('edit', row) }, '编辑'),
        h('button', { class: 'rule-btn rule-btn--del', onClick: () => removeRule(row) }, '删除')
      ])
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
        <NButton size="small" type="primary" @click="openRuleModal('add')">新增规则</NButton>
      </template>
      <NDataTable
        :columns="ruleColumns"
        :data="store.pointsEarningRules"
        :bordered="false"
        :row-key="(row: any) => row.id"
      />
    </NCard>

    <NModal v-model:show="ruleModalVisible" preset="card" :title="ruleMode === 'edit' ? '编辑规则' : '新增规则'" class="w-480px">
      <NForm label-placement="left" :label-width="80">
        <NFormItem label="行为"><NInput v-model:value="ruleForm.action" /></NFormItem>
        <NFormItem label="奖励"><NInput v-model:value="ruleForm.reward" /></NFormItem>
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
.rule-btn--edit {
  background: #e8f5e9;
  color: #53882c;
}
.rule-btn--del {
  background: #fdecea;
  color: #e65a5a;
}
</style>

