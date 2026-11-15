<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Plus, Refresh } from '@element-plus/icons-vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import LoadingState from '@/components/LoadingState.vue'
import type { AiModel } from '@/api/ai'
import {
  createAdminAiModel,
  deleteAdminAiModel,
  listAdminAiModels,
  listRemoteModels,
  testAdminAiConnection,
  testAdminAiModel,
  updateAdminAiModel,
  type ConnectionTestResult,
  type ModelConfigPayload,
} from './adminApi'
import { resolveErrorMessage } from '@/api/client'

const { t } = useI18n()

const models = ref<AiModel[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<string | null>(null)
const form = ref<ModelConfigPayload>(emptyForm())
const formError = ref<string | null>(null)
const testing = ref(false)
const testResult = ref<ConnectionTestResult | null>(null)
const remoteModels = ref<string[]>([])

function emptyForm(): ModelConfigPayload {
  return {
    name: '',
    baseUrl: 'http://localhost:1234/v1',
    apiKey: '',
    model: '',
    visionEnabled: false,
    reasoningEnabled: false,
    thinkingMode: 'REASONING_EFFORT',
    isDefault: false,
    enabled: true,
    timeoutSeconds: 60,
    maxTokens: 2048,
  }
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    models.value = await listAdminAiModels()
  } catch (cause) {
    error.value = resolveErrorMessage(cause, t('admin.aiModels.loadFailed'))
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editingId.value = null
  form.value = emptyForm()
  formError.value = null
  testResult.value = null
  remoteModels.value = []
  dialogVisible.value = true
}

function openEdit(model: AiModel): void {
  editingId.value = model.id
  form.value = {
    name: model.name,
    baseUrl: model.baseUrl,
    apiKey: '',
    model: model.model,
    visionEnabled: model.visionEnabled,
    reasoningEnabled: model.reasoningEnabled,
    thinkingMode: model.thinkingMode ?? 'REASONING_EFFORT',
    isDefault: model.isDefault,
    enabled: model.enabled,
    timeoutSeconds: model.timeoutSeconds ?? 60,
    maxTokens: model.maxTokens ?? 2048,
  }
  formError.value = null
  testResult.value = null
  remoteModels.value = []
  dialogVisible.value = true
}

function validateForm(): string | null {
  if (!form.value.name.trim()) return t('admin.aiModels.nameRequired')
  if (!form.value.baseUrl.trim()) return t('admin.aiModels.baseUrlRequired')
  if (!form.value.model.trim()) return t('admin.aiModels.modelRequired')
  if (!/^https?:\/\//.test(form.value.baseUrl.trim())) return t('admin.aiModels.baseUrlInvalid')
  return null
}

async function save(): Promise<void> {
  formError.value = validateForm()
  if (formError.value) return
  saving.value = true
  try {
    const payload: ModelConfigPayload = {
      ...form.value,
      name: form.value.name.trim(),
      baseUrl: form.value.baseUrl.trim(),
      model: form.value.model.trim(),
      apiKey: form.value.apiKey?.trim() ? form.value.apiKey.trim() : null,
    }
    if (editingId.value) {
      await updateAdminAiModel(editingId.value, payload)
      ElMessage.success(t('admin.aiModels.updated'))
    } else {
      await createAdminAiModel(payload)
      ElMessage.success(t('admin.aiModels.created'))
    }
    dialogVisible.value = false
    await load()
  } catch (cause) {
    formError.value = resolveErrorMessage(cause, t('profile.saveFailed'))
  } finally {
    saving.value = false
  }
}

async function testFormConnection(): Promise<void> {
  if (!form.value.baseUrl.trim()) {
    formError.value = t('admin.aiModels.testFirst')
    return
  }
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await testAdminAiConnection({
      baseUrl: form.value.baseUrl.trim(),
      apiKey: form.value.apiKey?.trim() || null,
      model: form.value.model?.trim() || null,
    })
    if (testResult.value.success && remoteModels.value.length === 0 && editingId.value) {
      void loadRemoteModels()
    }
  } catch (cause) {
    testResult.value = { success: false, latencyMs: 0, models: [], message: resolveErrorMessage(cause, t('admin.aiModels.testFailed')) }
  } finally {
    testing.value = false
  }
}

async function testSaved(model: AiModel): Promise<void> {
  try {
    const result = await testAdminAiModel(model.id)
    if (result.success) {
      ElMessage.success(`${result.message}（${result.latencyMs}ms）`)
    } else {
      ElMessage.warning(result.message)
    }
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.aiModels.testFailed')))
  }
}

async function loadRemoteModels(): Promise<void> {
  if (!editingId.value) return
  try {
    remoteModels.value = await listRemoteModels(editingId.value)
    if (remoteModels.value.length === 0) ElMessage.info(t('admin.aiModels.emptyUpstream'))
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.aiModels.listFailed')))
  }
}

async function remove(model: AiModel): Promise<void> {
  const confirmed = await ElMessageBox.confirm(
    t('admin.aiModels.deleteConfirm', { name: model.name }),
    t('admin.aiModels.deleteTitle'),
    {
    type: 'warning',
  }).catch(() => false)
  if (!confirmed) return
  try {
    await deleteAdminAiModel(model.id)
    ElMessage.success(t('admin.aiModels.deleted'))
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, t('admin.aiModels.deleteFailed')))
  }
}

onMounted(load)
</script>

<template>
  <section class="admin-ai">
    <header class="admin-section-head">
      <div>
        <h2 class="admin-section-title">{{ t('admin.aiModels.title') }}</h2>
        <p class="admin-section-subtitle">{{ t('admin.aiModels.subtitle') }}</p>
      </div>
      <div class="admin-section-actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">{{ t('admin.aiModels.create') }}</el-button>
      </div>
    </header>

    <LoadingState v-if="loading && models.length === 0" :rows="3" :tip="t('admin.models.loading')" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <EmptyState
      v-else-if="models.length === 0"
      :title="t('admin.models.empty')"
      :description="t('admin.models.emptyHint')"
    >
      <template #action>
        <el-button type="primary" @click="openCreate">{{ t('admin.aiModels.create') }}</el-button>
      </template>
    </EmptyState>
    <div v-else class="model-list">
      <article v-for="model in models" :key="model.id" class="model-card">
        <div class="model-card__head">
          <div>
            <p class="model-card__name">
              {{ model.name }}
              <el-tag v-if="model.isDefault" size="small" type="success" effect="plain">{{ t('admin.aiModels.default') }}</el-tag>
              <el-tag v-if="!model.enabled" size="small" type="info" effect="plain">{{ t('admin.aiModels.disabled') }}</el-tag>
            </p>
            <p class="model-card__meta">{{ model.model }} · {{ model.baseUrl }}</p>
          </div>
          <div class="model-card__tags">
            <el-tag v-if="model.visionEnabled" size="small" effect="plain">{{ t('ai.chat.visionTag') }}</el-tag>
            <el-tag v-if="model.reasoningEnabled" size="small" effect="plain" type="warning">{{ t('ai.chat.deepThinking') }}</el-tag>
            <el-tag size="small" effect="plain" :type="model.apiKeyConfigured ? 'success' : 'info'">
              {{ model.apiKeyConfigured ? t('admin.aiModels.keyConfigured') : t('admin.aiModels.keyNotNeeded') }}
            </el-tag>
          </div>
        </div>
        <div class="model-card__actions">
          <el-button size="small" link type="primary" @click="openEdit(model)">{{ t('common.edit') }}</el-button>
          <el-button size="small" link :icon="Connection" @click="testSaved(model)">{{ t('admin.aiModels.test') }}</el-button>
          <el-button size="small" link type="danger" @click="remove(model)">{{ t('common.delete') }}</el-button>
        </div>
      </article>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? t('admin.aiModels.edit') : t('admin.aiModels.new')"
      width="640px"
      append-to-body
      destroy-on-close
    >
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item :label="t('admin.models.namePlaceholder')">
            <el-input v-model="form.name" maxlength="64" :placeholder="t('admin.models.nameExample')" />
          </el-form-item>
          <el-form-item :label="t('admin.models.modelId')">
            <el-input v-model="form.model" maxlength="128" :placeholder="t('admin.models.modelExample')" />
          </el-form-item>
        </div>
        <el-form-item label="Base URL">
          <el-input v-model="form.baseUrl" maxlength="500" placeholder="http://localhost:1234/v1" />
        </el-form-item>
        <el-form-item :label="t('admin.models.apiKeyHint')">
          <el-input v-model="form.apiKey" type="password" show-password maxlength="200" :placeholder="t('admin.models.apiKeyLocal')" />
        </el-form-item>
        <div class="form-grid form-grid--three">
          <el-form-item :label="t('admin.models.timeout')">
            <el-input-number v-model="form.timeoutSeconds" :min="1" :max="600" />
          </el-form-item>
          <el-form-item :label="t('admin.models.maxTokens')">
            <el-input-number v-model="form.maxTokens" :min="1" :max="128000" :step="256" />
          </el-form-item>
          <el-form-item :label="t('admin.models.defaultModel')">
            <el-switch v-model="form.isDefault" />
          </el-form-item>
        </div>
        <div class="form-grid form-grid--three">
          <el-form-item :label="t('admin.models.enabled')">
            <el-switch v-model="form.enabled" />
          </el-form-item>
          <el-form-item :label="t('admin.models.vision')">
            <el-switch v-model="form.visionEnabled" />
          </el-form-item>
          <el-form-item :label="t('admin.models.reasoning')">
            <el-switch v-model="form.reasoningEnabled" />
          </el-form-item>
        </div>
        <el-form-item v-if="form.reasoningEnabled" :label="t('admin.models.thinkingMode')">
          <el-select v-model="form.thinkingMode" class="thinking-mode">
            <el-option :label="t('admin.models.thinkingEffort')" value="REASONING_EFFORT" />
            <el-option label="chat_template_kwargs（LM Studio / vLLM + Qwen3）" value="CHAT_TEMPLATE" />
            <el-option :label="t('admin.models.thinkingBoth')" value="BOTH" />
            <el-option :label="t('admin.models.thinkingNone')" value="NONE" />
          </el-select>
          <p class="form-hint">
            {{ t('admin.aiModels.thinkingHint') }}
          </p>
        </el-form-item>

        <el-alert v-if="formError" class="form-alert" type="error" :title="formError" show-icon :closable="false" />
        <el-alert
          v-if="testResult"
          class="form-alert"
          :type="testResult.success ? 'success' : 'warning'"
          :title="
            testResult.success
              ? t('admin.aiModels.connectOkMs', { ms: testResult.latencyMs })
              : t('admin.aiModels.connectFailed')
          "
          :description="
            testResult.success
              ? t('admin.aiModels.availableList', {
                  models: testResult.models.join(', ') || t('admin.aiModels.notReturned'),
                })
              : testResult.message
          "
          show-icon
          :closable="false"
        />
        <el-alert
          v-if="editingId && remoteModels.length > 0"
          class="form-alert"
          type="info"
          :title="t('admin.aiModels.upstreamList', { models: remoteModels.join(', ') })"
          :closable="false"
        />
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button :loading="testing" @click="testFormConnection">{{ t('admin.aiModels.test') }}</el-button>
        <el-button v-if="editingId" @click="loadRemoteModels">{{ t('admin.aiModels.listModels') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped lang="scss">
.admin-section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--isles-space-4);
}

.admin-section-title {
  font-size: var(--isles-font-lg);
  font-weight: 600;
}

.admin-section-subtitle {
  margin-top: var(--isles-space-1);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-sm);
}

.admin-section-actions {
  display: flex;
  gap: var(--isles-space-2);
}

.model-list {
  display: flex;
  flex-direction: column;
  gap: var(--isles-space-3);
}

.model-card {
  padding: var(--isles-space-4);
  background: var(--isles-surface);
  border: 1px solid var(--isles-border);
  border-radius: var(--isles-radius-lg);
}

.model-card__head {
  display: flex;
  gap: var(--isles-space-3);
  align-items: flex-start;
  justify-content: space-between;
}

.model-card__name {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-2);
  align-items: center;
  font-size: var(--isles-font-md);
  font-weight: 600;
}

.model-card__meta {
  margin-top: var(--isles-space-1);
  color: var(--isles-text-muted);
  font-size: var(--isles-font-sm);
  word-break: break-all;
}

.model-card__tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--isles-space-1);
  justify-content: flex-end;
}

.model-card__actions {
  display: flex;
  gap: var(--isles-space-2);
  justify-content: flex-end;
  margin-top: var(--isles-space-2);
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--isles-space-3);
}

.form-grid--three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.form-alert {
  margin-top: var(--isles-space-2);
}

@media (max-width: 720px) {
  .form-grid,
  .form-grid--three {
    grid-template-columns: 1fr;
  }
}
</style>
