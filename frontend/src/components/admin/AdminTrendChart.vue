<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed } from 'vue'
import type { AdminTrendPoint } from '@/pages/admin/adminApi'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    points: AdminTrendPoint[]
    /** 图表高度（px） */
    height?: number
  }>(),
  { height: 220 },
)

const WIDTH = 720
const PADDING = { top: 12, right: 12, bottom: 24, left: 36 }

interface Series {
  key: keyof Omit<AdminTrendPoint, 'date'>
  labelKey: string
  color: string
}

const SERIES: Series[] = [
  { key: 'activeUsers', labelKey: 'admin.trend.activeUsers', color: '#3b82f6' },
  { key: 'newUsers', labelKey: 'admin.trend.newUsers', color: '#10b981' },
  { key: 'newPosts', labelKey: 'admin.trend.newPosts', color: '#f59e0b' },
  { key: 'newComments', labelKey: 'admin.trend.newComments', color: '#8b5cf6' },
]

const innerWidth = WIDTH - PADDING.left - PADDING.right
const innerHeight = computed(() => props.height - PADDING.top - PADDING.bottom)

/** 纵轴上限：四条曲线共用，避免各自缩放导致误读 */
const maxValue = computed(() => {
  let max = 0
  for (const point of props.points) {
    for (const series of SERIES) {
      max = Math.max(max, Number(point[series.key] ?? 0))
    }
  }
  return max === 0 ? 1 : max
})

function xOf(index: number): number {
  const count = props.points.length
  if (count <= 1) return PADDING.left + innerWidth / 2
  return PADDING.left + (innerWidth * index) / (count - 1)
}

function yOf(value: number): number {
  const ratio = value / maxValue.value
  return PADDING.top + innerHeight.value * (1 - ratio)
}

function pathOf(series: Series): string {
  return props.points
    .map((point, index) => {
      const x = xOf(index)
      const y = yOf(Number(point[series.key] ?? 0))
      return `${index === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
}

/** 纵轴刻度（4 段） */
const yTicks = computed(() => {
  const steps = 4
  return Array.from({ length: steps + 1 }, (_, index) => {
    const value = (maxValue.value * index) / steps
    return { value: Math.round(value), y: yOf(value) }
  })
})

/** 横轴只标注首、中、末，避免 30 个日期挤在一起 */
const xLabels = computed(() => {
  const count = props.points.length
  if (count === 0) return []
  const indexes = count <= 3 ? props.points.map((_, index) => index) : [0, Math.floor((count - 1) / 2), count - 1]
  return indexes.map((index) => ({
    x: xOf(index),
    label: props.points[index].date?.slice(5) ?? '',
    // 首尾标签贴边会溢出画布，改成向内对齐
    anchor: index === 0 ? 'start' : index === count - 1 ? 'end' : 'middle',
  }))
})

const totals = computed(() =>
  SERIES.map((series) => ({
    ...series,
    total: props.points.reduce((sum, point) => sum + Number(point[series.key] ?? 0), 0),
  })),
)
</script>

<template>
  <div class="admin-trend">
    <div class="admin-trend__legend">
      <span v-for="item in totals" :key="item.key" class="admin-trend__legend-item">
        <i class="admin-trend__dot" :style="{ background: item.color }" />
        {{ t(item.labelKey) }}
        <strong>{{ item.total }}</strong>
      </span>
    </div>

    <svg
      class="admin-trend__chart"
      :viewBox="`0 0 ${WIDTH} ${height}`"
      role="img"
      :aria-label="t('admin.trend.aria')"
    >
      <g class="admin-trend__grid">
        <line
          v-for="tick in yTicks"
          :key="`grid-${tick.value}`"
          :x1="PADDING.left"
          :x2="WIDTH - PADDING.right"
          :y1="tick.y"
          :y2="tick.y"
        />
      </g>

      <g class="admin-trend__axis">
        <text v-for="tick in yTicks" :key="`y-${tick.value}`" :x="PADDING.left - 6" :y="tick.y + 3" text-anchor="end">
          {{ tick.value }}
        </text>
        <text v-for="label in xLabels" :key="`x-${label.label}`" :x="label.x" :y="height - 6" :text-anchor="label.anchor">
          {{ label.label }}
        </text>
      </g>

      <path
        v-for="series in SERIES"
        :key="series.key"
        class="admin-trend__line"
        :d="pathOf(series)"
        :stroke="series.color"
      />

      <!-- 每个数据点一个透明热区：原生 tooltip 兼顾可访问性，无需额外 JS -->
      <g v-for="(point, index) in points" :key="`hit-${point.date}`">
        <rect
          :x="xOf(index) - 6"
          :y="PADDING.top"
          width="12"
          :height="innerHeight"
          fill="transparent"
        >
          <title>{{ point.date }} · {{ t('admin.trend.activeUsers') }} {{ point.activeUsers }} · {{ t('admin.trend.newUsers') }} {{ point.newUsers }} · {{ t('admin.trend.newPosts') }} {{ point.newPosts }} · {{ t('admin.trend.newComments') }} {{ point.newComments }}</title>
        </rect>
      </g>
    </svg>
  </div>
</template>

<style scoped lang="scss">
.admin-trend__legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-4);
  margin-bottom: var(--isles-space-2);
  font-size: var(--isles-font-sm);
  color: var(--isles-text-secondary);
}

.admin-trend__legend-item {
  display: inline-flex;
  gap: 6px;
  align-items: center;

  strong {
    color: var(--isles-text);
  }
}

.admin-trend__dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.admin-trend__chart {
  display: block;
  width: 100%;
  height: auto;
}

.admin-trend__grid line {
  stroke: var(--isles-border);
  stroke-dasharray: 3 3;
}

.admin-trend__axis text {
  fill: var(--isles-text-muted);
  font-size: 10px;
}

.admin-trend__line {
  fill: none;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
