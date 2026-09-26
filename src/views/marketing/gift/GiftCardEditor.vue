<script setup lang="ts">
defineOptions({
  name: 'GiftCardEditor'
});

import { computed, reactive, ref, watch } from 'vue';
import { NButton, NForm, NFormItem, NImage, NInput, NInputNumber, NModal, NSelect, NTag, NUpload } from 'naive-ui';
import type { UploadCustomRequestOptions } from 'naive-ui';
import {
  createGiftCardFace,
  fetchGiftCardGroups,
  updateGiftCardFace,
  uploadGiftCardImage
} from '@/service/api/crud';
import type { GiftCardFace, GiftCardFacePayload } from '@/service/api/crud';

interface DenominationDraft {
  id?: number;
  amount: number | null;
  salePrice: number | null;
  referenced: boolean;
}

interface GiftCardForm {
  groupId: string | null;
  cardName: string;
  cardImage: string;
  sort: number;
  denominations: DenominationDraft[];
}

const props = defineProps<{
  show: boolean;
  face: GiftCardFace | null;
}>();

const emit = defineEmits<{
  'update:show': [boolean];
  saved: [];
}>();

const submitting = ref(false);
const uploading = ref(false);
const groupsLoading = ref(false);
const groupOptions = ref<{ label: string; value: string }[]>([]);

const form = reactive<GiftCardForm>({
  groupId: null,
  cardName: '',
  cardImage: '',
  sort: 0,
  denominations: [createDenomination()]
});

const title = computed(() => (props.face?.faceId ? '编辑礼品卡' : '新增礼品卡'));

function createDenomination(): DenominationDraft {
  return { amount: null, salePrice: null, referenced: false };
}

function resetForm() {
  const face = props.face;
  form.groupId = face?.groupId == null ? null : String(face.groupId);
  form.cardName = face?.cardName ?? '';
  form.cardImage = face?.cardImage ?? '';
  form.sort = Number(face?.sort ?? 0);
  form.denominations = (face?.denominations ?? [])
    .filter(item => !item.deleted)
    .map(item => ({
      id: item.id,
      amount: Number(item.amount) / 100,
      salePrice: Number(item.salePrice) / 100,
      referenced: Boolean(item.referenced)
    }));
  if (!form.denominations.length) {
    form.denominations = [createDenomination()];
  }
}

async function loadGroups() {
  groupsLoading.value = true;
  try {
    const groups = await fetchGiftCardGroups();
    groupOptions.value = [...groups]
      .sort((a, b) => Number(a.sort ?? 0) - Number(b.sort ?? 0))
      .map(group => ({ label: `${group.name}（${group.code}）`, value: String(group.code) }));
  } catch (error: any) {
    groupOptions.value = [];
    window.$message?.error(error?.message || '礼品卡分组加载失败');
  } finally {
    groupsLoading.value = false;
  }
}

watch(
  () => [props.show, props.face],
  async ([show]) => {
    if (!show) return;
    resetForm();
    await loadGroups();
  },
  { immediate: true }
);

function close() {
  emit('update:show', false);
}

function addDenomination() {
  form.denominations.push(createDenomination());
}

function removeDenomination(index: number) {
  if (form.denominations.length <= 1) {
    window.$message?.warning('至少保留一条面额');
    return;
  }
  form.denominations.splice(index, 1);
}

function toFen(value: number | null): number {
  return Math.round(Number(value ?? 0) * 100);
}

function validate(): string | null {
  if (!form.groupId) return '请选择卡种分组';
  if (!form.cardName.trim()) return '请输入卡面名称';
  if (!form.cardImage) return '请上传卡面图片';
  if (!form.denominations.length) return '请至少添加一条面额';

  for (let index = 0; index < form.denominations.length; index += 1) {
    const item = form.denominations[index];
    const amount = toFen(item.amount);
    const salePrice = toFen(item.salePrice);
    if (amount <= 0) return `第 ${index + 1} 条面值必须大于 0`;
    if (salePrice <= 0) return `第 ${index + 1} 条售价必须大于 0`;
    if (salePrice > amount) return `第 ${index + 1} 条售价不能高于面值`;
  }
  return null;
}

async function handleUpload(options: UploadCustomRequestOptions) {
  const file = options.file.file;
  if (!(file instanceof File)) {
    options.onError();
    return;
  }

  uploading.value = true;
  try {
    const result = await uploadGiftCardImage(file);
    form.cardImage = result.url;
    options.onFinish();
    window.$message?.success('图片上传成功');
  } catch (error: any) {
    options.onError();
    window.$message?.error(error?.message || '图片上传失败');
  } finally {
    uploading.value = false;
  }
}

async function submit() {
  const errorMessage = validate();
  if (errorMessage) {
    window.$message?.warning(errorMessage);
    return;
  }

  const payload: GiftCardFacePayload = {
    groupId: String(form.groupId),
    cardName: form.cardName.trim(),
    cardImage: form.cardImage,
    sort: Number(form.sort ?? 0),
    denominations: form.denominations.map(item => {
      const denomination: { id?: number; amount: number; salePrice: number } = {
        amount: toFen(item.amount),
        salePrice: toFen(item.salePrice)
      };
      if (item.id != null) denomination.id = item.id;
      return denomination;
    })
  };

  submitting.value = true;
  try {
    if (props.face?.faceId) {
      await updateGiftCardFace(props.face.faceId, payload);
      window.$message?.success('礼品卡已保存');
    } else {
      await createGiftCardFace(payload);
      window.$message?.success('礼品卡已新增，默认下架');
    }
    emit('saved');
    close();
  } catch (error: any) {
    window.$message?.error(error?.message || '礼品卡保存失败');
  } finally {
    submitting.value = false;
  }
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    :title="title"
    class="w-760px"
    @update:show="emit('update:show', $event)"
  >
    <NForm label-placement="left" :label-width="96">
      <NFormItem label="卡种分组" required>
        <NSelect
          v-model:value="form.groupId"
          :options="groupOptions"
          :loading="groupsLoading"
          placeholder="请选择分组"
          filterable
        />
      </NFormItem>

      <NFormItem label="卡面名称" required>
        <NInput v-model:value="form.cardName" maxlength="40" show-count placeholder="请输入卡面名称" />
      </NFormItem>

      <NFormItem label="卡面图片" required>
        <div class="image-field">
          <NImage
            v-if="form.cardImage"
            :src="form.cardImage"
            width="180"
            height="110"
            object-fit="cover"
            class="image-preview"
          />
          <div v-else class="image-empty">暂无图片</div>
          <NUpload
            accept=".jpg,.jpeg,.png,image/jpeg,image/png"
            :show-file-list="false"
            :custom-request="handleUpload"
          >
            <NButton :loading="uploading">{{ form.cardImage ? '重新上传' : '上传图片' }}</NButton>
          </NUpload>
        </div>
      </NFormItem>

      <NFormItem label="排序">
        <NInputNumber v-model:value="form.sort" :min="0" :precision="0" class="w-160px" />
      </NFormItem>

      <NFormItem label="面额配置" required>
        <div class="denomination-editor">
          <div class="denomination-head">
            <span>面值（元）</span>
            <span>售价（元）</span>
            <span>操作</span>
          </div>
          <div v-for="(item, index) in form.denominations" :key="item.id ?? `new-${index}`" class="denomination-row">
            <div class="denomination-amount">
              <NInputNumber
                v-model:value="item.amount"
                :disabled="item.referenced"
                :min="0.01"
                :precision="2"
                placeholder="请输入面值"
              />
              <NTag v-if="item.referenced" size="small" type="warning" :bordered="false">已引用 · 面值锁定</NTag>
            </div>
            <NInputNumber
              v-model:value="item.salePrice"
              :min="0.01"
              :precision="2"
              placeholder="请输入售价"
            />
            <NButton quaternary type="error" @click="removeDenomination(index)">删除</NButton>
          </div>
          <div class="denomination-tip">至少一条；售价必须大于 0 且不高于面值。已引用面额仅允许修改售价。</div>
          <NButton dashed type="primary" @click="addDenomination">+ 添加面额</NButton>
        </div>
      </NFormItem>
    </NForm>

    <div class="modal-actions">
      <NButton @click="close">取消</NButton>
      <NButton type="primary" :loading="submitting" @click="submit">确定</NButton>
    </div>
  </NModal>
</template>

<style scoped>
.image-field {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 12px;
}

.image-preview {
  overflow: hidden;
  border-radius: 8px;
}

.image-empty {
  display: flex;
  width: 180px;
  height: 110px;
  align-items: center;
  justify-content: center;
  border: 1px dashed #d9d9d3;
  border-radius: 8px;
  color: #9b9b96;
  background: #fafaf8;
}

.denomination-editor {
  width: 100%;
}

.denomination-head,
.denomination-row {
  display: grid;
  grid-template-columns: minmax(140px, 1fr) minmax(140px, 1fr) 80px;
  gap: 12px;
}

.denomination-head {
  margin-bottom: 6px;
  color: #747570;
  font-size: 13px;
}

.denomination-row {
  align-items: center;
  margin-bottom: 8px;
}

.denomination-amount {
  display: flex;
  align-items: center;
  gap: 8px;
}

.denomination-amount :deep(.n-input-number) {
  min-width: 0;
  flex: 1;
}

.denomination-tip {
  margin: 4px 0 12px;
  color: #9b9b96;
  font-size: 12px;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}
</style>