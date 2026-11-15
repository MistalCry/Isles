<script setup lang="ts">
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
    error.value = resolveErrorMessage(cause, 'AI 模型配置加载失败')
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
  if (!form.value.name.trim()) return '请填写配置名称'
  if (!form.value.baseUrl.trim()) return '请填写 Base URL'
  if (!form.value.model.trim()) return '请填写模型 ID'
  if (!/^https?:\/\//.test(form.value.baseUrl.trim())) return 'Base URL 需以 http:// 或 https:// 开头'
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
      ElMessage.success('模型配置已更新')
    } else {
      await createAdminAiModel(payload)
      ElMessage.success('模型配置已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (cause) {
    formError.value = resolveErrorMessage(cause, '保存失败')
  } finally {
    saving.value = false
  }
}

async function testFormConnection(): Promise<void> {
  if (!form.value.baseUrl.trim()) {
    formError.value = '请先填写 Base URL'
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
    testResult.value = { success: false, latencyMs: 0, models: [], message: resolveErrorMessage(cause, '测试连接失败') }
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
    ElMessage.error(resolveErrorMessage(cause, '测试连接失败'))
  }
}

async function loadRemoteModels(): Promise<void> {
  if (!editingId.value) return
  try {
    remoteModels.value = await listRemoteModels(editingId.value)
    if (remoteModels.value.length === 0) ElMessage.info('上游未返回模型列表')
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, '模型列表获取失败'))
  }
}

async function remove(model: AiModel): Promise<void> {
  const confirmed = await ElMessageBox.confirm(`确定删除模型配置「${model.name}」？`, '删除配置', {
    type: 'warning',
  }).catch(() => false)
  if (!confirmed) return
  try {
    await deleteAdminAiModel(model.id)
    ElMessage.success('已删除')
    await load()
  } catch (cause) {
    ElMessage.error(resolveErrorMessage(cause, '删除失败'))
  }
}

onMounted(load)
</script>

<template>
  <section class="admin-ai">
    <header class="admin-section-head">
      <div>
        <h2 class="admin-section-title">AI 模型配置</h2>
        <p class="admin-section-subtitle">LMStudio / OpenAI 兼容协议；API Key 只写不读（仅返回掩码）</p>
      </div>
      <div class="admin-section-actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增配置</el-button>
      </div>
    </header>

    <LoadingState v-if="loading && models.length === 0" :rows="3" tip="正在加载模型配置…" />
    <ErrorState v-else-if="error" :message="error" @retry="load" />
    <EmptyState
      v-else-if="models.length === 0"
      title="还没有配置 AI 模型"
      description="配置 LMStudio（如 http://localhost:1234/v1）或任意 OpenAI 兼容服务后，频道问问与频道助手即可使用。"
    >
      <template #action>
        <el-button type="primary" @click="openCreate">新增配置</el-button>
      </template>
    </EmptyState>
    <div v-else class="model-list">
      <article v-for="model in models" :key="model.id" class="model-card">
        <div class="model-card__head">
          <div>
            <p class="model-card__name">
              {{ model.name }}
              <el-tag v-if="model.isDefault" size="small" type="success" effect="plain">默认</el-tag>
              <el-tag v-if="!model.enabled" size="small" type="info" effect="plain">已停用</el-tag>
            </p>
            <p class="model-card__meta">{{ model.model }} · {{ model.baseUrl }}</p>
          </div>
          <div class="model-card__tags">
            <el-tag v-if="model.visionEnabled" size="small" effect="plain">视觉</el-tag>
            <el-tag v-if="model.reasoningEnabled" size="small" effect="plain" type="warning">深度思考</el-tag>
            <el-tag size="small" effect="plain" :type="model.apiKeyConfigured ? 'success' : 'info'">
              {{ model.apiKeyConfigured ? '已配置密钥' : '无需密钥' }}
            </el-tag>
          </div>
        </div>
        <div class="model-card__actions">
          <el-button size="small" link type="primary" @click="openEdit(model)">编辑</el-button>
          <el-button size="small" link :icon="Connection" @click="testSaved(model)">测试连接</el-button>
          <el-button size="small" link type="danger" @click="remove(model)">删除</el-button>
        </div>
      </article>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑模型配置' : '新增模型配置'"
      width="640px"
      append-to-body
      destroy-on-close
    >
      <el-form label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="配置名称">
            <el-input v-model="form.name" maxlength="64" placeholder="如：本地 LMStudio" />
          </el-form-item>
          <el-form-item label="模型 ID">
            <el-input v-model="form.model" maxlength="128" placeholder="如：qwen2.5-vl-7b" />
          </el-form-item>
        </div>
        <el-form-item label="Base URL">
          <el-input v-model="form.baseUrl" maxlength="500" placeholder="http://localhost:1234/v1" />
        </el-form-item>
        <el-form-item label="API Key（留空表示不修改/无需鉴权）">
          <el-input v-model="form.apiKey" type="password" show-password maxlength="200" placeholder="本地 LMStudio 通常无需填写" />
        </el-form-item>
        <div class="form-grid form-grid--three">
          <el-form-item label="超时（秒）">
            <el-input-number v-model="form.timeoutSeconds" :min="1" :max="600" />
          </el-form-item>
          <el-form-item label="最大输出 token">
            <el-input-number v-model="form.maxTokens" :min="1" :max="128000" :step="256" />
          </el-form-item>
          <el-form-item label="默认模型">
            <el-switch v-model="form.isDefault" />
          </el-form-item>
        </div>
        <div class="form-grid form-grid--three">
          <el-form-item label="启用">
            <el-switch v-model="form.enabled" />
          </el-form-item>
          <el-form-item label="支持图片（视觉）">
            <el-switch v-model="form.visionEnabled" />
          </el-form-item>
          <el-form-item label="支持深度思考">
            <el-switch v-model="form.reasoningEnabled" />
          </el-form-item>
        </div>
        <el-form-item v-if="form.reasoningEnabled" label="思考参数下发方式">
          <el-select v-model="form.thinkingMode" class="thinking-mode">
            <el-option label="reasoning_effort（OpenAI / 云端推理模型）" value="REASONING_EFFORT" />
            <el-option label="chat_template_kwargs（LM Studio / vLLM + Qwen3）" value="CHAT_TEMPLATE" />
            <el-option label="两者都发（兼容性最好）" value="BOTH" />
            <el-option label="不发送（模型自带开关，无法通过参数控制）" value="NONE" />
          </el-select>
          <p class="form-hint">
            关闭「深度思考」时后端会显式下发关闭参数；若模型仍继续思考，多半是这里选错了方式。
          </p>
        </el-form-item>

        <el-alert v-if="formError" class="form-alert" type="error" :title="formError" show-icon :closable="false" />
        <el-alert
          v-if="testResult"
          class="form-alert"
          :type="testResult.success ? 'success' : 'warning'"
          :title="testResult.success ? `连接成功（${testResult.latencyMs}ms）` : '连接失败'"
          :description="testResult.success ? `可用模型：${testResult.models.join('、') || '未返回'}` : testResult.message"
          show-icon
          :closable="false"
        />
        <el-alert
          v-if="editingId && remoteModels.length > 0"
          class="form-alert"
          type="info"
          :title="`上游模型：${remoteModels.join('、')}`"
          :closable="false"
        />
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button :loading="testing" @click="testFormConnection">测试连接</el-button>
        <el-button v-if="editingId" @click="loadRemoteModels">拉取模型列表</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
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
