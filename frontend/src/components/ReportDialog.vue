<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { REPORT_REASONS } from '@/constants/app'

const props = defineProps<{
  modelValue: boolean
  targetType: 'post' | 'comment'
  targetId: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const reason = ref<string>(REPORT_REASONS[0])
const description = ref('')

watch(visible, (open) => {
  if (open) {
    reason.value = REPORT_REASONS[0]
    description.value = ''
  }
})

/** M1 占位：不伪造提交结果，明确告知审核流程将在 M2 接入。 */
function handleSubmit(): void {
  ElMessage.info(`举报入口（${props.targetType === 'post' ? '帖子' : '评论'}）已预留，处置流程将在 M2 上线`)
  visible.value = false
}
</script>

<template>
  <el-dialog v-model="visible" title="举报" width="440px" append-to-body>
    <p class="report-dialog__hint">请选择举报原因，平台会在核实后处理。恶意举报可能影响账号信用。</p>
    <el-radio-group v-model="reason" class="report-dialog__reasons">
      <el-radio v-for="item in REPORT_REASONS" :key="item" :value="item">{{ item }}</el-radio>
    </el-radio-group>
    <el-input
      v-model="description"
      type="textarea"
      :rows="3"
      maxlength="200"
      show-word-limit
      placeholder="补充说明（选填）"
    />
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="handleSubmit">提交举报</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.report-dialog__hint {
  margin-bottom: var(--isles-space-3);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
}

.report-dialog__reasons {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
  margin-bottom: var(--isles-space-4);
}
</style>
