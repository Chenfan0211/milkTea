<script setup lang="ts">

defineOptions({
  name: 'marketing_referral'
});

import { reactive, watch } from 'vue';
import { useAdminStore } from '@/store/modules/admin';

const store = useAdminStore();

const form = reactive({
  inviteCodePrefix: '',
  firstOrderPoints: 3,
  firstOrderCouponAmount: 3,
  socialStarThreshold: 5,
  socialStarProduct: '',
  recommenderThreshold: 10,
  recommenderRebateRate: 5
});

watch(
  () => store.referralConfig,
  val => {
    if (!val) return;
    form.inviteCodePrefix = val.inviteCodePrefix ?? '';
    form.firstOrderPoints = val.firstOrderPoints ?? 3;
    form.firstOrderCouponAmount = val.firstOrderCouponAmount ?? 3;
    form.socialStarThreshold = val.socialStarThreshold ?? 5;
    form.socialStarProduct = val.socialStarProduct ?? '';
    form.recommenderThreshold = val.recommenderThreshold ?? 10;
    form.recommenderRebateRate = val.recommenderRebateRate ?? 5;
  },
  { immediate: true, deep: true }
);

function save() {
  store.saveReferralConfig({ ...form });
  window.$message?.success('分享规则已保存');
}
</script>

<template>
  <div class="referral-page">
    <NCard :bordered="false" title="分享有礼规则">
      <NForm label-placement="left" :label-width="180" class="referral-form">
        <NFormItem label="邀请码前缀">
          <NInput v-model:value="form.inviteCodePrefix" placeholder="如 WLG" />
        </NFormItem>
        <NFormItem label="双方首单奖励（时光币）">
          <NInputNumber v-model:value="form.firstOrderPoints" :min="0" class="w-160px" />
        </NFormItem>
        <NFormItem label="双方首单奖励券（元）">
          <NInputNumber v-model:value="form.firstOrderCouponAmount" :min="0" class="w-160px" />
        </NFormItem>
        <NFormItem label="社交达人邀请人数">
          <NInputNumber v-model:value="form.socialStarThreshold" :min="1" class="w-160px" />
        </NFormItem>
        <NFormItem label="社交达人指定产品">
          <NInput v-model:value="form.socialStarProduct" placeholder="指定产品名称（可空）" />
        </NFormItem>
        <NFormItem label="时光推荐官邀请人数">
          <NInputNumber v-model:value="form.recommenderThreshold" :min="1" class="w-160px" />
        </NFormItem>
        <NFormItem label="推荐官返利比例（%）">
          <NInputNumber v-model:value="form.recommenderRebateRate" :min="0" :max="100" class="w-160px" />
        </NFormItem>
        <NFormItem>
          <NButton type="primary" @click="save">保存分享规则</NButton>
        </NFormItem>
      </NForm>
    </NCard>
  </div>
</template>

<style scoped>
.referral-page {
  max-width: 700px;
}
.referral-form {
  max-width: 500px;
}
</style>
