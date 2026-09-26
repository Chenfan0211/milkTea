<script setup lang="ts">
import { computed, h } from 'vue';
import { NSelect } from 'naive-ui';

defineOptions({ name: 'BenefitIconSelect' });

const props = withDefaults(
  defineProps<{
    modelValue?: string | null;
    placeholder?: string;
    disabled?: boolean;
  }>(),
  {
    modelValue: null,
    placeholder: '选择图标（可空）',
    disabled: false
  }
);

const emit = defineEmits<{
  'update:modelValue': [value: string | null];
}>();

/**
 * 只允许小程序端已经同步到 assets/icons/lucide 的会员权益图标。
 * 图标名仅用于 filterable 搜索和 aria-label，界面只渲染 SVG。
 */
const BENEFIT_ICON_WHITELIST = [
  'badge-percent',
  'badge-japanese-yen',
  'badge-japanese-yen-brand',
  'star',
  'star-brand',
  'ticket',
  'ticket-percent',
  'gift',
  'gift-brand',
  'crown-gold',
  'medal',
  'gem',
  'award',
  'wallet',
  'wallet-brand',
  'trending-up',
  'trending-up-brand',
  'shopping-bag',
  'headset',
  'calendar-check',
  'calendar-check-brand',
  'heart',
  'heart-active',
  'handshake',
  'graduation-cap',
  'message-square-heart'
] as const;

const iconModules = import.meta.glob('../../../../user-h5/assets/icons/lucide/*.svg', {
  eager: true,
  query: '?url',
  import: 'default'
}) as Record<string, string>;

function iconNameFromPath(path: string) {
  return path.split('/').pop()?.replace(/\.svg$/, '') ?? '';
}

const iconUrlMap = Object.fromEntries(
  Object.entries(iconModules).map(([path, url]) => [iconNameFromPath(path), url])
) as Record<string, string>;

const options = computed(() => {
  const names = [...BENEFIT_ICON_WHITELIST] as string[];
  const current = String(props.modelValue ?? '').trim();
  if (current && !names.includes(current)) names.unshift(current);
  return names.map(name => ({ label: name, value: name }));
});

function renderIcon(name?: string | null) {
  const value = String(name ?? '').trim();
  const url = value ? iconUrlMap[value] : '';
  const label = value || '未选择图标';
  return h(
    'span',
    {
      class: ['benefit-icon-view', !url ? 'benefit-icon-view--missing' : ''],
      role: 'img',
      'aria-label': label
    },
    url ? [h('img', { src: url, alt: '', draggable: false })] : []
  );
}

function renderLabel(option: any) {
  return h('span', { class: 'benefit-icon-label', 'aria-label': option?.label }, renderIcon(option?.value));
}

function renderOption(option: any) {
  return h(
    'span',
    {
      class: 'benefit-icon-option',
      'aria-label': option?.label
    },
    renderIcon(option?.value)
  );
}

function renderTag({ option, handleClose }: any) {
  return h('span', { class: 'benefit-icon-tag', 'aria-label': option?.label }, [
    renderIcon(option?.value),
    h(
      'span',
      {
        class: 'benefit-icon-tag__close',
        role: 'button',
        tabindex: 0,
        'aria-label': '清除图标',
        onClick: (event: MouseEvent) => {
          event.stopPropagation();
          handleClose?.();
        },
        onKeydown: (event: KeyboardEvent) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            event.stopPropagation();
            handleClose?.();
          }
        }
      },
      '×'
    )
  ]);
}

function updateValue(value: string | null) {
  emit('update:modelValue', value || null);
}
</script>

<template>
  <NSelect
    :value="props.modelValue ?? null"
    :options="options"
    :placeholder="props.placeholder"
    :disabled="props.disabled"
    :render-label="renderLabel"
    :render-option="renderOption"
    :render-tag="renderTag"
    clearable
    filterable
    @update:value="updateValue"
  />
</template>

<style scoped>
:deep(.benefit-icon-view) {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  border-radius: 6px;
  background: #f5f7f3;
  overflow: hidden;
}

:deep(.benefit-icon-view img) {
  width: 18px;
  height: 18px;
  display: block;
  object-fit: contain;
}

:deep(.benefit-icon-view--missing) {
  position: relative;
  border: 1px solid #dcdfd7;
  background: #fafbf9;
}

:deep(.benefit-icon-view--missing::after) {
  content: '';
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c7cbc2;
}

:deep(.benefit-icon-label),
:deep(.benefit-icon-option),
:deep(.benefit-icon-tag) {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

:deep(.benefit-icon-option) {
  min-height: 24px;
}

:deep(.benefit-icon-tag__close) {
  color: #8b8f86;
  cursor: pointer;
  font-size: 16px;
  line-height: 1;
}

:deep(.benefit-icon-tag__close:hover) {
  color: #555;
}
</style>