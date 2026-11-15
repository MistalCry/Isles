<script setup lang="ts">
import { computed } from 'vue'
import type { TrendPoint } from '@/types/models'

const props = withDefaults(
  defineProps<{
    points: TrendPoint[]
    metric?: 'visits' | 'posts' | 'members'
    label?: string
  }>(),
  {
    metric: 'visits',
    label: '趋势',
  },
)

const values = computed(() => props.points.map((point) => Number(point[props.metric] ?? 0)))
const maxValue = computed(() => Math.max(1, ...values.value))
const bars = computed(() =>
  props.points.map((point, index) => ({
    date: point.date,
    label: point.date ? point.date.slice(5) : `第 ${index + 1} 天`,
    value: values.value[index] ?? 0,
    height: `${Math.max(3, Math.round(((values.value[index] ?? 0) / maxValue.value) * 100))}%`,
  })),
)
</script>

<template>
  <div class="trend-chart">
    <div class="trend-chart__head">
      <strong>{{ label }}</strong>
      <span class="muted">峰值 {{ maxValue }}</span>
    </div>
    <div v-if="points.length > 0" class="trend-chart__bars" role="img" :aria-label="`${label} 趋势图`">
      <div v-for="bar in bars" :key="bar.label + bar.value" class="trend-chart__bar">
        <span class="trend-chart__value">{{ bar.value }}</span>
        <span class="trend-chart__column" :style="{ height: bar.height }" />
        <span class="trend-chart__label">{{ bar.label }}</span>
      </div>
    </div>
    <p v-else class="muted trend-chart__empty">暂无趋势数据</p>
  </div>
</template>

<style scoped lang="scss">
.trend-chart__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--isles-space-3);
  font-size: var(--isles-font-sm);
}

.trend-chart__bars {
  display: flex;
  gap: var(--isles-space-2);
  align-items: flex-end;
  height: 170px;
  overflow-x: auto;
}

.trend-chart__bar {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  min-width: 28px;
  height: 100%;
}

.trend-chart__value {
  margin-bottom: 2px;
  color: var(--isles-text-muted);
  font-size: 10px;
}

.trend-chart__column {
  width: 60%;
  min-height: 3px;
  background: linear-gradient(180deg, var(--isles-primary), var(--isles-primary-soft));
  border-radius: 4px 4px 0 0;
}

.trend-chart__label {
  margin-top: 4px;
  color: var(--isles-text-muted);
  font-size: 10px;
  white-space: nowrap;
}

.trend-chart__empty {
  font-size: var(--isles-font-xs);
}
</style>
