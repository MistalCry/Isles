<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { manageApi } from '@/api'
import { resolveErrorMessage } from '@/api/client'
import { reportReasons } from '@/constants/app'

const { t } = useI18n()

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

const reasons = computed(() => reportReasons())
const reason = ref<string>(reasons.value[0])
const description = ref('')
const submitting = ref(false)
const errorMessage = ref<string | null>(null)

watch(visible, (open) => {
  if (open) {
    reason.value = reasons.value[0]
    description.value = ''
    errorMessage.value = null
  }
})

async function handleSubmit(): Promise<void> {
  if (!reason.value) {
    ElMessage.warning(t('report.reasonRequired'))
    return
  }
  submitting.value = true
  errorMessage.value = null
  try {
    await manageApi.submitReport({
      targetType: props.targetType === 'post' ? 'POST' : 'COMMENT',
      targetId: props.targetId,
      reason: reason.value,
      description: description.value.trim() || undefined,
    })
    ElMessage.success(t('report.submitted'))
    visible.value = false
  } catch (err) {
    errorMessage.value = resolveErrorMessage(err, t('report.failed'))
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="t('post.detail.report')" width="440px" append-to-body>
    <p class="report-dialog__hint">{{ t('report.hint') }}</p>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" show-icon class="report-dialog__alert" />
    <el-radio-group v-model="reason" class="report-dialog__reasons">
      <el-radio v-for="item in reasons" :key="item" :value="item">{{ item }}</el-radio>
    </el-radio-group>
    <el-input
      v-model="description"
      type="textarea"
      :rows="3"
      maxlength="200"
      show-word-limit
      :placeholder="t('report.notePlaceholder')"
    />
    <template #footer>
      <el-button @click="visible = false">{{ t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">{{ t('report.submit') }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped lang="scss">
.report-dialog__hint {
  margin-bottom: var(--isles-space-3);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
}

.report-dialog__alert {
  margin-bottom: var(--isles-space-3);
}

.report-dialog__reasons {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-2);
  margin-bottom: var(--isles-space-4);
}
</style>
