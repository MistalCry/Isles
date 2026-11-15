<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, UserFilled } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingState from '@/components/LoadingState.vue'
import UserAvatar from '@/components/UserAvatar.vue'
import ImageCropUpload from '@/components/media/ImageCropUpload.vue'
import {
  ensureAssistantAccount,
  getAssistantSettings,
  listAssistantModels,
  listAssistantTaskLogs,
  runAssistantTask,
  saveAssistantSettings,
  type AiTaskLog,
  type AiTaskType,
  type AssistantSettings,
  type AssistantSettingsPayload,
  type SelectableAiModel,
} from '@/api/ai'
import { resolveErrorMessage } from '@/api/client'
import { formatDateTime } from '@/utils/format'

const { t } = useI18n()

const props = withDefaults(
  defineProps<{
    channelId: string
    channelName?: string | null
  }>(),
  { channelName: null },
)

const taskOptions: Array<{ type: AiTaskType; label: string }> = [
  { type: 'DAILY_SUMMARY', label: t('assistant.page.dailyShort') },
  { type: 'WEEKLY_SUMMARY', label: t('assistant.page.weeklyShort') },
  { type: 'MONTHLY_CONTRIBUTORS', label: t('assistant.page.monthly') },
  { type: 'AUTO_POST', label: t('assistant.page.autoPost') },
  { type: 'AUTO_COMMENT', label: t('assistant.page.autoComment') },
  { type: 'AUTO_LIKE', label: t('assistant.page.autoLike') },
]

const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)
const settings = ref<AssistantSettings | null>(null)
const form = ref<AssistantSettingsPayload>(emptyForm())

const logsLoading = ref(false)
const logsError = ref<string | null>(null)
const logs = ref<AiTaskLog[]>([])
const logsTotal = ref(0)
const logPage = ref(1)
const logPageSize = ref(10)
const logTaskType = ref<AiTaskType | ''>('')
const runningTask = ref<AiTaskType | null>(null)
/** 助手可选模型；接口不可用时只保留「跟随平台默认」 */
const models = ref<SelectableAiModel[]>([])
const modelsLoading = ref(false)
const creatingAccount = ref(false)

const assistantConfigured = computed(() => Boolean(settings.value?.assistantUserId))
const assistantName = computed(() => form.value.nickname || t('ai.chat.assistant'))

function emptyForm(): AssistantSettingsPayload {
  return {
    nickname: t('ai.chat.assistant'),
    avatarUrl: '',
    coverUrl: '',
    bio: '',
    persona: '',
    enabled: false,
    summaryDailyEnabled: false,
    summaryWeeklyEnabled: false,
    monthlyContributorsEnabled: false,
    autoPostEnabled: false,
    autoCommentEnabled: false,
    autoLikeEnabled: false,
    autoReplyEnabled: true,
    dailySummaryHour: 9,
    autoPostPerDay: 1,
    autoCommentPerDay: 3,
    autoLikePerDay: 10,
    modelConfigId: '',
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const data = await getAssistantSettings(props.channelId)
    settings.value = data
    form.value = {
      nickname: data.nickname || t('ai.chat.assistant'),
      avatarUrl: data.avatarUrl ?? '',
      coverUrl: data.coverUrl ?? '',
      bio: data.bio ?? '',
      persona: data.persona ?? '',
      enabled: data.enabled,
      summaryDailyEnabled: data.summaryDailyEnabled,
      summaryWeeklyEnabled: data.summaryWeeklyEnabled,
      monthlyContributorsEnabled: data.monthlyContributorsEnabled,
      autoPostEnabled: data.autoPostEnabled,
      autoCommentEnabled: data.autoCommentEnabled,
      autoLikeEnabled: data.autoLikeEnabled,
      autoReplyEnabled: data.autoReplyEnabled ?? true,
      dailySummaryHour: data.dailySummaryHour ?? 9,
      autoPostPerDay: data.autoPostPerDay ?? 1,
      autoCommentPerDay: data.autoCommentPerDay ?? 3,
      autoLikePerDay: data.autoLikePerDay ?? 10,
      modelConfigId: data.modelConfigId ?? '',
    }
    await Promise.all([loadLogs(), loadModels()])
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('assistant.page.loadFailed'))
  } finally {
    loading.value = false
  }
}

/** 可选模型：接口不可用时降级为只有「跟随平台默认」，不阻塞其它设置 */
async function loadModels(): Promise<void> {
  modelsLoading.value = true
  try {
    models.value = await listAssistantModels(props.channelId)
  } catch {
    models.value = []
  } finally {
    modelsLoading.value = false
  }
}

function modelOptionLabel(model: SelectableAiModel): string {
  return model.model ? `${model.name} · ${model.model}` : model.name
}

async function save(): Promise<void> {
  saving.value = true
  try {
    const data = await saveAssistantSettings(props.channelId, {
      ...form.value,
      nickname: form.value.nickname?.trim() || t('ai.chat.assistant'),
      avatarUrl: form.value.avatarUrl?.trim() || null,
      coverUrl: form.value.coverUrl?.trim() || null,
      bio: form.value.bio?.trim() || null,
      persona: form.value.persona?.trim() || null,
      // 空串 = 跟随平台默认（后端约定 null 表示不修改，这里始终显式提交）
      modelConfigId: form.value.modelConfigId || '',
    })
    settings.value = data
    ElMessage.success(t('assistant.page.saved'))
    await loadLogs()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('profile.saveFailed')))
  } finally {
    saving.value = false
  }
}

async function createAssistantAccount(): Promise<void> {
  creatingAccount.value = true
  try {
    const info = await ensureAssistantAccount(props.channelId)
    if (info.assistantUserId) {
      ElMessage.success(t('assistant.page.accountReady'))
      await load()
    }
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('assistant.page.accountFailed')))
  } finally {
    creatingAccount.value = false
  }
}

async function runTask(taskType: AiTaskType): Promise<void> {
  const label = taskOptions.find((item) => item.type === taskType)?.label ?? taskType
  const confirmed = await ElMessageBox.confirm(
    t('assistant.page.runConfirm', { label }),
    t('assistant.page.runTask'),
    { type: 'info' },
  ).catch(() => false)
  if (!confirmed) return
  runningTask.value = taskType
  try {
    const taskLog = await runAssistantTask(props.channelId, taskType)
    if (taskLog.status === 'SUCCESS') {
      ElMessage.success(taskLog.resultText || t('assistant.page.runSuccess'))
    } else if (taskLog.status === 'SKIPPED') {
      ElMessage.warning(taskLog.resultText || t('assistant.page.runSkipped'))
    } else {
      ElMessage.error(taskLog.errorMessage || t('assistant.page.runFailed'))
    }
    await loadLogs()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('assistant.page.runTriggerFailed')))
  } finally {
    runningTask.value = null
  }
}

async function loadLogs(): Promise<void> {
  logsLoading.value = true
  logsError.value = null
  try {
    const data = await listAssistantTaskLogs(props.channelId, {
      taskType: logTaskType.value || null,
      page: logPage.value,
      size: logPageSize.value,
    })
    logs.value = data.items
    logsTotal.value = data.total
  } catch (cause) {
    logsError.value = resolveErrorMessage(cause, t('assistant.page.logsFailed'))
  } finally {
    logsLoading.value = false
  }
}

function taskStatusType(status: AiTaskLog['status']): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'SKIPPED') return 'warning'
  return 'info'
}

function taskStatusLabel(status: AiTaskLog['status']): string {
  const labels: Record<AiTaskLog['status'], string> = {
    RUNNING: t('assistant.page.running'),
    SUCCESS: t('assistant.page.success'),
    SKIPPED: t('assistant.page.skipped'),
    FAILED: t('assistant.page.failed'),
  }
  return labels[status] ?? status
}

/** 被动接话不是可手动触发的任务，只出现在日志里 */
const EXTRA_TASK_LABELS: Record<string, string> = {
  REPLY_COMMENT: t('assistant.page.autoReply'),
  REPLY_MENTION: t('assistant.page.replyMention'),
}

function taskTypeLabel(type: AiTaskType): string {
  return EXTRA_TASK_LABELS[type] ?? taskOptions.find((item) => item.type === type)?.label ?? type
}

onMounted(load)
</script>

<template>
  <section class="channel-ai-settings">
    <header class="channel-ai-settings__head">
      <div>
        <h2 class="channel-ai-settings__title">{{ t('manage.nav.assistant') }}</h2>
        <p class="channel-ai-settings__subtitle">
          {{
            channelName
              ? t('assistant.page.subtitleOf', { name: channelName })
              : t('assistant.page.subtitle')
          }}
        </p>
      </div>
      <div class="channel-ai-settings__actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('assistant.page.save') }}</el-button>
      </div>
    </header>

    <LoadingState v-if="loading && !settings" :rows="5" :tip="t('assistant.page.loading')" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <template v-else>
      <el-alert
        v-if="!assistantConfigured"
        class="channel-ai-settings__alert"
        type="info"
        show-icon
        :closable="false"
        :title="t('assistant.page.noAccount')"
        :description="t('assistant.page.noAccountHint')"
      />

      <div class="settings-grid">
        <section class="settings-card">
          <h3 class="settings-card__title">{{ t('assistant.page.identity') }}</h3>
          <div class="assistant-preview">
            <UserAvatar :src="form.avatarUrl || null" :name="assistantName" :size="48" square />
            <div>
              <p class="assistant-preview__name">{{ assistantName }}</p>
              <p class="assistant-preview__bio">{{ form.bio || t('assistant.page.noBio') }}</p>
            </div>
            <el-button
              size="small"
              :icon="UserFilled"
              :loading="creatingAccount"
              @click="createAssistantAccount"
            >
              {{ assistantConfigured ? t('assistant.page.fixAccount') : t('assistant.page.createAccount') }}
            </el-button>
          </div>
          <el-form label-position="top">
            <div class="form-grid">
              <el-form-item :label="t('assistant.page.nickname')">
                <el-input v-model="form.nickname" maxlength="32" />
              </el-form-item>
              <el-form-item :label="t('assistant.page.avatar')" class="assistant-avatar-item">
                <ImageCropUpload
                  v-model="form.avatarUrl"
                  :aspect-ratio="1"
                  :max-output-width="1024"
                  :box-width="200"
                  :preview-width="72"
                  shape="circle"
                  :label="t('media.crop.avatarLabel')"
                  :hint="t('media.crop.squareHint')"
                />
              </el-form-item>
            </div>
            <el-form-item :label="t('assistant.page.bio')">
              <el-input v-model="form.bio" maxlength="500" show-word-limit />
            </el-form-item>
            <el-form-item :label="t('assistant.page.persona')">
              <el-input v-model="form.persona" type="textarea" :rows="4" maxlength="2000" show-word-limit />
            </el-form-item>
            <el-form-item :label="t('assistant.page.master')">
              <el-switch v-model="form.enabled" />
              <span class="form-hint">{{ t('assistant.page.masterHint') }}</span>
            </el-form-item>
            <el-form-item :label="t('assistant.page.model')">
              <el-select v-model="form.modelConfigId" :loading="modelsLoading" class="model-select">
                <el-option :label="t('assistant.page.modelDefault')" value="" />
                <el-option v-for="item in models" :key="item.id" :label="modelOptionLabel(item)" :value="item.id" />
              </el-select>
              <span class="form-hint">
                {{ models.length === 0 ? t('assistant.page.modelEmpty') : t('assistant.page.modelHint') }}
              </span>
            </el-form-item>
          </el-form>
        </section>

        <section class="settings-card">
          <h3 class="settings-card__title">{{ t('assistant.page.tasks') }}</h3>
          <div class="task-switch">
            <el-switch v-model="form.summaryDailyEnabled" />
            <div class="task-switch__meta">
              <p>{{ t('assistant.page.daily') }}</p>
              <small>{{ t('assistant.page.dailyHourHint', { hour: form.dailySummaryHour }) }}</small>
            </div>
          </div>
          <div class="task-switch">
            <el-switch v-model="form.summaryWeeklyEnabled" />
            <div class="task-switch__meta">
              <p>{{ t('assistant.page.weekly') }}</p>
              <small>{{ t('assistant.page.weeklyHint') }}</small>
            </div>
          </div>
          <div class="task-switch">
            <el-switch v-model="form.monthlyContributorsEnabled" />
            <div class="task-switch__meta">
              <p>{{ t('assistant.page.monthly') }}</p>
              <small>{{ t('assistant.page.monthlyHint') }}</small>
            </div>
          </div>
          <div class="task-switch">
            <el-switch v-model="form.autoPostEnabled" />
            <div class="task-switch__meta">
              <p>{{ t('assistant.page.autoPost') }}</p>
              <small>{{ t('assistant.page.autoPostHint') }}</small>
            </div>
          </div>
          <div class="task-switch">
            <el-switch v-model="form.autoCommentEnabled" />
            <div class="task-switch__meta">
              <p>{{ t('assistant.page.autoComment') }}</p>
              <small>{{ t('assistant.page.autoCommentHint') }}</small>
            </div>
          </div>
          <div class="task-switch">
            <el-switch v-model="form.autoLikeEnabled" />
            <div class="task-switch__meta">
              <p>{{ t('assistant.page.autoLike') }}</p>
              <small>{{ t('assistant.page.autoLikeHint') }}</small>
            </div>
          </div>
          <div class="task-switch">
            <el-switch v-model="form.autoReplyEnabled" />
            <div class="task-switch__meta">
              <p>{{ t('assistant.page.autoReply') }}</p>
              <small>{{ t('assistant.page.autoReplyHint') }}</small>
            </div>
          </div>

          <el-alert
            class="task-conditions"
            type="info"
            :closable="false"
            show-icon
            :title="t('assistant.page.conditions')"
          >
            <template #default>
              <ul class="task-conditions__list">
                <li>{{ t('assistant.page.cond1') }}</li>
                <li>{{ t('assistant.page.cond2') }}</li>
                <li>{{ t('assistant.page.cond3') }}</li>
                <li>{{ t('assistant.page.cond4') }}</li>
                <li>{{ t('assistant.page.cond5') }}</li>
              </ul>
            </template>
          </el-alert>

          <h4 class="settings-card__subtitle">{{ t('assistant.page.limits') }}</h4>
          <el-form label-position="top" class="frequency-form" @submit.prevent>
            <div class="form-grid">
              <el-form-item :label="t('assistant.page.hour')">
                <el-input-number v-model="form.dailySummaryHour" :min="0" :max="23" controls-position="right" />
              </el-form-item>
              <el-form-item :label="t('assistant.page.postPerDay')">
                <el-input-number v-model="form.autoPostPerDay" :min="0" :max="50" controls-position="right" />
              </el-form-item>
              <el-form-item :label="t('assistant.page.commentPerDay')">
                <el-input-number v-model="form.autoCommentPerDay" :min="0" :max="100" controls-position="right" />
              </el-form-item>
              <el-form-item :label="t('assistant.page.likePerDay')">
                <el-input-number v-model="form.autoLikePerDay" :min="0" :max="200" controls-position="right" />
              </el-form-item>
            </div>
          </el-form>
          <p class="form-hint frequency-form__hint">{{ t('assistant.page.limitsHint') }}</p>
        </section>
      </div>

      <section class="settings-card settings-card--logs">
        <div class="logs-head">
          <div>
            <h3 class="settings-card__title">{{ t('assistant.page.logs') }}</h3>
            <p class="settings-card__desc">{{ t('assistant.page.logsHint') }}</p>
          </div>
          <el-select v-model="logTaskType" size="small" :placeholder="t('assistant.page.allTasks')" clearable @change="loadLogs">
            <el-option v-for="item in taskOptions" :key="item.type" :label="item.label" :value="item.type" />
          </el-select>
        </div>

        <div class="task-run-buttons">
          <el-button
            v-for="item in taskOptions"
            :key="item.type"
            size="small"
            :loading="runningTask === item.type"
            :disabled="Boolean(runningTask) && runningTask !== item.type"
            @click="runTask(item.type)"
          >
            {{
              runningTask === item.type
                ? t('assistant.page.generating')
                : t('assistant.page.runNow', { label: item.label })
            }}
          </el-button>
        </div>

        <LoadingState v-if="logsLoading && logs.length === 0" :rows="2" />
        <ErrorState v-else-if="logsError" :message="logsError" @retry="loadLogs" />
        <EmptyState
          v-else-if="logs.length === 0"
          compact
          :title="t('assistant.page.noLogs')"
          :description="t('assistant.page.noLogsHint')"
        />
        <el-table v-else :data="logs" class="logs-table" row-key="id">
          <el-table-column :label="t('assistant.page.task')" width="120">
            <template #default="{ row }">{{ taskTypeLabel(row.taskType) }}</template>
          </el-table-column>
          <el-table-column :label="t('common.status')" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="taskStatusType(row.status)" effect="plain">
                {{ taskStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('assistant.page.result')" min-width="240">
            <template #default="{ row }">
              <p v-if="row.resultText">{{ row.resultText }}</p>
              <p v-if="row.errorMessage" class="text-danger">{{ row.errorMessage }}</p>
              <p v-if="!row.resultText && !row.errorMessage" class="muted">—</p>
            </template>
          </el-table-column>
          <el-table-column :label="t('assistant.page.periodKey')" width="140" prop="frequencyKey" />
          <el-table-column :label="t('common.time')" width="160">
            <template #default="{ row }">{{ formatDateTime(row.finishedAt || row.createdAt) }}</template>
          </el-table-column>
        </el-table>
        <footer v-if="logsTotal > logPageSize" class="logs-pager">
          <el-pagination
            v-model:current-page="logPage"
            :page-size="logPageSize"
            layout="prev, pager, next"
            :total="logsTotal"
            @current-change="loadLogs"
          />
        </footer>
      </section>
    </template>
  </section>
</template>

<style scoped lang="scss">
.channel-ai-settings__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--isles-space-4);
}

.channel-ai-settings__title {
  font-size: var(--isles-font-lg);
  font-weight: 600;
}

.channel-ai-settings__subtitle {
  margin-top: var(--isles-space-1);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-sm);
}

.channel-ai-settings__actions {
  display: flex;
  gap: var(--isles-space-2);
}

.channel-ai-settings__alert {
  margin-bottom: var(--isles-space-3);
}

.settings-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--isles-space-3);
  margin-bottom: var(--isles-space-3);
}

.settings-card {
  padding: var(--isles-space-4);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
}

.settings-card__title {
  margin-bottom: var(--isles-space-2);
  font-size: var(--isles-font-md);
  font-weight: 600;
}

.settings-card__subtitle {
  margin: var(--isles-space-3) 0 var(--isles-space-2);
  color: var(--isles-text-secondary);
  font-size: var(--isles-font-sm);
  font-weight: 600;
}

.settings-card__desc {
  margin-top: var(--isles-space-1);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.assistant-preview {
  display: flex;
  gap: var(--isles-space-3);
  align-items: center;
  padding: var(--isles-space-3);
  margin-bottom: var(--isles-space-3);
  background: var(--isles-surface-muted);
  border-radius: var(--isles-radius-md);
}

.assistant-preview__name {
  font-size: var(--isles-font-sm);
  font-weight: 600;
}

.assistant-preview__bio {
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.assistant-preview .el-button {
  margin-left: auto;
}

.task-conditions {
  margin-bottom: var(--isles-space-3);
}

.task-conditions__list {
  padding-left: 18px;
  margin: 4px 0 0;
  font-size: var(--isles-font-xs);
  line-height: 1.7;
}

.frequency-form :deep(.el-input-number) {
  width: 100%;
}

.frequency-form__hint {
  margin-top: var(--isles-space-2);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--isles-space-3);
}

/* 头像上传占满整行，避免预览图挤在半列里换行 */
.assistant-avatar-item {
  grid-column: 1 / -1;

  :deep(.el-form-item__content) {
    align-items: flex-start;
  }
}

.model-select {
  width: 100%;
}

.form-hint {
  margin-left: var(--isles-space-2);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-xs);
}

.task-switch {
  display: flex;
  gap: var(--isles-space-3);
  align-items: flex-start;
  padding: var(--isles-space-2) 0;
  border-bottom: 1px dashed var(--isles-border);
}

.task-switch__meta {
  p {
    font-size: var(--isles-font-sm);
  }

  small {
    color: var(--isles-text-muted);
    font-size: var(--isles-font-xs);
  }
}

.settings-card--logs {
  margin-top: var(--isles-space-2);
}

.logs-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--isles-space-2);
}

.task-run-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  margin-bottom: var(--isles-space-3);
}

.logs-table {
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
}

.logs-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--isles-space-2);
}

@media (max-width: 900px) {
  .settings-grid {
    grid-template-columns: 1fr;
  }
}
</style>
