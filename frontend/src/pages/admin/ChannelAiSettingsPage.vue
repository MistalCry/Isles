<script setup lang="ts">
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
  listAssistantTaskLogs,
  runAssistantTask,
  saveAssistantSettings,
  type AiTaskLog,
  type AiTaskType,
  type AssistantSettings,
  type AssistantSettingsPayload,
} from '@/api/ai'
import { resolveErrorMessage } from '@/api/client'
import { formatDateTime } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    channelId: string
    channelName?: string | null
  }>(),
  { channelName: null },
)

const taskOptions: Array<{ type: AiTaskType; label: string }> = [
  { type: 'DAILY_SUMMARY', label: '日报' },
  { type: 'WEEKLY_SUMMARY', label: '周报' },
  { type: 'MONTHLY_CONTRIBUTORS', label: '月度贡献榜' },
  { type: 'AUTO_POST', label: '自动发帖' },
  { type: 'AUTO_COMMENT', label: '自动评论' },
  { type: 'AUTO_LIKE', label: '自动点赞' },
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
const creatingAccount = ref(false)

const assistantConfigured = computed(() => Boolean(settings.value?.assistantUserId))
const assistantName = computed(() => form.value.nickname || '频道助手')

function emptyForm(): AssistantSettingsPayload {
  return {
    nickname: '频道助手',
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
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const data = await getAssistantSettings(props.channelId)
    settings.value = data
    form.value = {
      nickname: data.nickname || '频道助手',
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
    }
    await loadLogs()
  } catch (cause) {
    error.value = resolveErrorMessage(cause, '频道助手设置加载失败（需要频道主/超级管理员权限）')
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  saving.value = true
  try {
    const data = await saveAssistantSettings(props.channelId, {
      ...form.value,
      nickname: form.value.nickname?.trim() || '频道助手',
      avatarUrl: form.value.avatarUrl?.trim() || null,
      coverUrl: form.value.coverUrl?.trim() || null,
      bio: form.value.bio?.trim() || null,
      persona: form.value.persona?.trim() || null,
    })
    settings.value = data
    ElMessage.success('频道助手设置已保存')
    await loadLogs()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, '保存失败'))
  } finally {
    saving.value = false
  }
}

async function createAssistantAccount(): Promise<void> {
  creatingAccount.value = true
  try {
    const info = await ensureAssistantAccount(props.channelId)
    if (info.assistantUserId) {
      ElMessage.success('助手账号已就绪，可以开始自动运营')
      await load()
    }
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, '助手账号创建失败'))
  } finally {
    creatingAccount.value = false
  }
}

async function runTask(taskType: AiTaskType): Promise<void> {
  const label = taskOptions.find((item) => item.type === taskType)?.label ?? taskType
  const confirmed = await ElMessageBox.confirm(
    `确定立即执行「${label}」任务？任务会调用已配置的 AI 模型，并按开关与频率上限校验。`,
    '执行任务',
    { type: 'info' },
  ).catch(() => false)
  if (!confirmed) return
  runningTask.value = taskType
  try {
    const taskLog = await runAssistantTask(props.channelId, taskType)
    if (taskLog.status === 'SUCCESS') {
      ElMessage.success(taskLog.resultText || '任务执行成功')
    } else if (taskLog.status === 'SKIPPED') {
      ElMessage.warning(taskLog.resultText || '任务已跳过')
    } else {
      ElMessage.error(taskLog.errorMessage || '任务执行失败')
    }
    await loadLogs()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, '任务触发失败'))
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
    logsError.value = resolveErrorMessage(cause, '执行日志加载失败')
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
    RUNNING: '执行中',
    SUCCESS: '成功',
    SKIPPED: '已跳过',
    FAILED: '失败',
  }
  return labels[status] ?? status
}

/** 被动接话不是可手动触发的任务，只出现在日志里 */
const EXTRA_TASK_LABELS: Record<string, string> = {
  REPLY_COMMENT: '被动接话',
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
        <h2 class="channel-ai-settings__title">频道助手设置</h2>
        <p class="channel-ai-settings__subtitle">
          配置助手身份与自动运营任务{{ channelName ? `（频道：${channelName}）` : '' }}
        </p>
      </div>
      <div class="channel-ai-settings__actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存设置</el-button>
      </div>
    </header>

    <LoadingState v-if="loading && !settings" :rows="5" tip="正在加载频道助手设置…" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <template v-else>
      <el-alert
        v-if="!assistantConfigured"
        class="channel-ai-settings__alert"
        type="info"
        show-icon
        :closable="false"
        title="尚未创建助手虚拟账号"
        description="保存设置后点击「创建/修复助手账号」，助手将以普通成员身份加入频道，用于自动发帖、评论与点赞。"
      />

      <div class="settings-grid">
        <section class="settings-card">
          <h3 class="settings-card__title">助手身份</h3>
          <div class="assistant-preview">
            <UserAvatar :src="form.avatarUrl || null" :name="assistantName" :size="48" square />
            <div>
              <p class="assistant-preview__name">{{ assistantName }}</p>
              <p class="assistant-preview__bio">{{ form.bio || '还没有填写简介' }}</p>
            </div>
            <el-button
              size="small"
              :icon="UserFilled"
              :loading="creatingAccount"
              @click="createAssistantAccount"
            >
              {{ assistantConfigured ? '修复助手账号' : '创建助手账号' }}
            </el-button>
          </div>
          <el-form label-position="top">
            <div class="form-grid">
              <el-form-item label="助手昵称">
                <el-input v-model="form.nickname" maxlength="32" />
              </el-form-item>
              <el-form-item label="助手头像" class="assistant-avatar-item">
                <ImageCropUpload
                  v-model="form.avatarUrl"
                  :aspect-ratio="1"
                  :output-width="256"
                  :box-width="200"
                  :preview-width="72"
                  shape="circle"
                  label="上传头像"
                  hint="建议正方形，1:1"
                />
              </el-form-item>
            </div>
            <el-form-item label="助手简介">
              <el-input v-model="form.bio" maxlength="500" show-word-limit />
            </el-form-item>
            <el-form-item label="助手设定（persona，用于生成内容的语气与角色）">
              <el-input v-model="form.persona" type="textarea" :rows="4" maxlength="2000" show-word-limit />
            </el-form-item>
            <el-form-item label="频道助手总开关">
              <el-switch v-model="form.enabled" />
              <span class="form-hint">关闭后频道页面板与自动任务都会停用（已生成内容不受影响）。</span>
            </el-form-item>
          </el-form>
        </section>

        <section class="settings-card">
          <h3 class="settings-card__title">自动运营任务</h3>
          <div class="task-switch">
            <el-switch v-model="form.summaryDailyEnabled" />
            <div class="task-switch__meta">
              <p>每日总结（日报）</p>
              <small>每天 {{ form.dailySummaryHour }}:00（UTC）生成频道日报帖子</small>
            </div>
          </div>
          <div class="task-switch">
            <el-switch v-model="form.summaryWeeklyEnabled" />
            <div class="task-switch__meta">
              <p>每周总结（周报）</p>
              <small>每周一生成频道周报帖子</small>
            </div>
          </div>
          <div class="task-switch">
            <el-switch v-model="form.monthlyContributorsEnabled" />
            <div class="task-switch__meta">
              <p>月度贡献榜</p>
              <small>每月 1 日生成月度贡献者榜单帖子</small>
            </div>
          </div>
          <div class="task-switch">
            <el-switch v-model="form.autoPostEnabled" />
            <div class="task-switch__meta">
              <p>自动发帖</p>
              <small>基于近期热门话题生成互动帖</small>
            </div>
          </div>
          <div class="task-switch">
            <el-switch v-model="form.autoCommentEnabled" />
            <div class="task-switch__meta">
              <p>自动评论</p>
              <small>以助手身份参与近期帖子讨论（AI 生成标识）</small>
            </div>
          </div>
          <div class="task-switch">
            <el-switch v-model="form.autoLikeEnabled" />
            <div class="task-switch__meta">
              <p>自动点赞</p>
              <small>为近期优质帖子点赞</small>
            </div>
          </div>
          <div class="task-switch">
            <el-switch v-model="form.autoReplyEnabled" />
            <div class="task-switch__meta">
              <p>被动接话</p>
              <small>有人回复助手的评论时，助手自动接着回复（计入每日评论限额）</small>
            </div>
          </div>

          <h4 class="settings-card__subtitle">频率与限额</h4>
          <el-form label-position="top" class="frequency-form" @submit.prevent>
            <div class="form-grid">
              <el-form-item label="日报执行小时（UTC）">
                <el-input-number v-model="form.dailySummaryHour" :min="0" :max="23" controls-position="right" />
              </el-form-item>
              <el-form-item label="自动发帖 / 天">
                <el-input-number v-model="form.autoPostPerDay" :min="0" :max="50" controls-position="right" />
              </el-form-item>
              <el-form-item label="自动评论 / 天">
                <el-input-number v-model="form.autoCommentPerDay" :min="0" :max="100" controls-position="right" />
              </el-form-item>
              <el-form-item label="自动点赞 / 天">
                <el-input-number v-model="form.autoLikePerDay" :min="0" :max="200" controls-position="right" />
              </el-form-item>
            </div>
          </el-form>
          <p class="form-hint frequency-form__hint">限额按 UTC 自然日统计；调度每小时检查一次，达到上限当天不再执行。</p>
        </section>
      </div>

      <section class="settings-card settings-card--logs">
        <div class="logs-head">
          <div>
            <h3 class="settings-card__title">手动试运行与执行日志</h3>
            <p class="settings-card__desc">任务执行结果落库（ai_task_logs）；同一周期任务幂等，未配置模型时会明确跳过。</p>
          </div>
          <el-select v-model="logTaskType" size="small" placeholder="全部任务" clearable @change="loadLogs">
            <el-option v-for="item in taskOptions" :key="item.type" :label="item.label" :value="item.type" />
          </el-select>
        </div>

        <div class="task-run-buttons">
          <el-button
            v-for="item in taskOptions"
            :key="item.type"
            size="small"
            :loading="runningTask === item.type"
            @click="runTask(item.type)"
          >
            立即执行：{{ item.label }}
          </el-button>
        </div>

        <LoadingState v-if="logsLoading && logs.length === 0" :rows="2" />
        <ErrorState v-else-if="logsError" :message="logsError" @retry="loadLogs" />
        <EmptyState
          v-else-if="logs.length === 0"
          compact
          title="还没有执行记录"
          description="任务按设定时间自动执行，也可以点击上方按钮立即试运行。"
        />
        <el-table v-else :data="logs" class="logs-table" row-key="id">
          <el-table-column label="任务" width="120">
            <template #default="{ row }">{{ taskTypeLabel(row.taskType) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="taskStatusType(row.status)" effect="plain">
                {{ taskStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="结果 / 错误" min-width="240">
            <template #default="{ row }">
              <p v-if="row.resultText">{{ row.resultText }}</p>
              <p v-if="row.errorMessage" class="text-danger">{{ row.errorMessage }}</p>
              <p v-if="!row.resultText && !row.errorMessage" class="muted">—</p>
            </template>
          </el-table-column>
          <el-table-column label="周期键" width="140" prop="frequencyKey" />
          <el-table-column label="时间" width="160">
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
