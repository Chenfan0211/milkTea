<script setup lang="ts">
import { ref, watch } from 'vue';

interface SpecOption {
  id: string;
  label: string;
  priceDelta: number;
  selected?: boolean;
}

interface SpecGroup {
  id: string;
  label: string;
  options: SpecOption[];
}

const props = defineProps<{ modelValue: SpecGroup[] }>();
const emit = defineEmits<{ 'update:modelValue': [value: SpecGroup[]] }>();

const groups = ref<SpecGroup[]>([]);

watch(
  () => props.modelValue,
  val => {
    groups.value = JSON.parse(JSON.stringify(val || []));
  },
  { immediate: true, deep: true }
);

function emitChange() {
  emit('update:modelValue', JSON.parse(JSON.stringify(groups.value)));
}

function onGroupLabelInput(group: SpecGroup) {
  if ((group.label || '').trim() === TIP_LABEL) {
    window.$message?.warning('温馨提示请在「商品详情-饮用提示」中维护，请勿在规格组中配置');
    group.label = '';
  }
  emitChange();
}

const TIP_LABEL = '温馨提示'

function genId() {
  return 'g' + Date.now() + Math.floor(Math.random() * 1000);
}

function addGroup() {
  groups.value.push({ id: genId(), label: '', options: [] });
  emitChange();
}

function removeGroup(index: number) {
  groups.value.splice(index, 1);
  emitChange();
}

function addOption(group: SpecGroup) {
  // 首个选项默认选中
  const isFirst = group.options.length === 0;
  group.options.push({ id: genId(), label: '', priceDelta: 0, selected: isFirst });
  emitChange();
}

function removeOption(group: SpecGroup, index: number) {
  group.options.splice(index, 1);
  emitChange();
}

function setSelected(group: SpecGroup, index: number) {
  group.options.forEach((o, i) => (o.selected = i === index));
  emitChange();
}
</script>

<template>
  <div class="spec-editor">
    <div class="spec-hint">温馨提示请在「商品详情-饮用提示」中维护，请勿在规格组中配置</div>
    <div v-if="!groups.length" class="spec-empty">暂无规格，点击下方按钮新增规格组</div>

    <div v-for="(group, gi) in groups" :key="group.id" class="spec-group">
      <div class="spec-group-head">
        <input
          v-model="group.label"
          class="spec-input group-label"
          placeholder="规格组名称（如：杯型）"
          @input="onGroupLabelInput(group)"
        />
        <button type="button" class="spec-remove" aria-label="删除规格组" @click="removeGroup(gi)">删除组</button>
      </div>

      <div class="spec-options">
        <div v-for="(opt, oi) in group.options" :key="opt.id" class="spec-option">
          <input
            type="radio"
            :name="'default-' + group.id"
            :checked="!!opt.selected"
            class="spec-radio"
            aria-label="设为默认选项"
            @change="setSelected(group, oi)"
          />
          <input v-model="opt.label" class="spec-input" placeholder="选项名（如：大杯）" @input="emitChange" />
          <input
            v-model="opt.priceDelta"
            type="number"
            step="0.01"
            class="spec-input spec-price"
            placeholder="加价(元)"
            @input="emitChange"
          />
          <button type="button" class="spec-remove" aria-label="删除选项" @click="removeOption(group, oi)">删</button>
        </div>
        <button type="button" class="spec-add-opt" @click="addOption(group)">+ 添加选项</button>
      </div>
    </div>

    <button type="button" class="spec-add-group" @click="addGroup">+ 新增规格组</button>
  </div>
</template>

<style scoped>
.spec-editor {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.spec-hint {
  color: #9b9b96;
  font-size: 12px;
  line-height: 1.5;
}
.spec-empty {
  color: #9b9b96;
  font-size: 13px;
}
.spec-group {
  border: 1px solid #eee;
  border-radius: 8px;
  padding: 12px;
}
.spec-group-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}
.spec-options {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-left: 4px;
}
.spec-option {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.spec-radio {
  width: 16px;
  height: 16px;
  cursor: pointer;
}
.spec-input {
  height: 32px;
  padding: 0 10px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 13px;
  color: #333;
  outline: none;
}
.spec-input:focus {
  border-color: #53882c;
}
.group-label {
  flex: 1;
}
.spec-price {
  width: 90px;
}
.spec-remove {
  height: 28px;
  flex-shrink: 0;
  padding: 0 10px;
  border: none;
  border-radius: 4px;
  background: #f5f5f5;
  color: #e65a5a;
  font-size: 12px;
  cursor: pointer;
}
.spec-add-opt,
.spec-add-group {
  height: 32px;
  padding: 0 14px;
  border: 1px dashed #53882c;
  border-radius: 4px;
  background: #fff;
  color: #53882c;
  font-size: 13px;
  cursor: pointer;
}
.spec-add-opt {
  align-self: flex-start;
}
</style>
