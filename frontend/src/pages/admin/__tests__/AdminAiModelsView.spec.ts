import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

/**
 * 回归：编辑模型并保存时，toolCallEnabled 必须原样回传。
 *
 * 后端该字段缺省 false，前端一旦漏传，编辑一次就会把已开启的工具调用静默关掉，
 * 而且界面上没有别的地方能打开它——这类「少一个字段」的回归只能靠测试兜住。
 */
const mocks = vi.hoisted(() => ({
  listAdminAiModels: vi.fn(),
  updateAdminAiModel: vi.fn(),
  createAdminAiModel: vi.fn(),
}))

vi.mock('@/pages/admin/adminApi', () => ({
  listAdminAiModels: mocks.listAdminAiModels,
  updateAdminAiModel: mocks.updateAdminAiModel,
  createAdminAiModel: mocks.createAdminAiModel,
  deleteAdminAiModel: vi.fn(),
  testAdminAiModel: vi.fn(),
  testAdminAiConnection: vi.fn(),
  listRemoteModels: vi.fn(),
  getAdminAiStatus: vi.fn(),
}))

import AdminAiModelsView from '@/pages/admin/AdminAiModelsView.vue'

const MODEL = {
  id: '01MODEL000000000000000001',
  name: '本地 Qwen3-VL 8B',
  baseUrl: 'http://localhost:1234/v1',
  model: 'qwen3-vl-8b-instruct',
  visionEnabled: true,
  reasoningEnabled: true,
  toolCallEnabled: true,
  thinkingMode: 'REASONING_EFFORT',
  isDefault: true,
  enabled: true,
  timeoutSeconds: 60,
  maxTokens: 2048,
  apiKeyConfigured: false,
  apiKeyMasked: null,
}

/** 局部桩：让开关可点击并回传布尔，便于断言「双向可控」 */
const stubs = {
  'el-switch': {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template:
      '<button type="button" class="el-switch-stub" :data-on="String(!!modelValue)" @click="$emit(\'update:modelValue\', !modelValue)" />',
  },
}

async function mountView() {
  mocks.listAdminAiModels.mockResolvedValue([MODEL])
  mocks.updateAdminAiModel.mockResolvedValue(MODEL)
  const wrapper = mount(AdminAiModelsView, { global: { stubs } })
  await flushPromises()
  return wrapper
}

function buttonByText(wrapper: Awaited<ReturnType<typeof mountView>>, text: string) {
  const button = wrapper.findAll('button').find((item) => item.text().trim() === text)
  if (!button) throw new Error(`未找到按钮「${text}」`)
  return button
}

describe('AdminAiModelsView 工具调用开关', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('编辑弹窗渲染「支持工具调用」开关，并回填后端值', async () => {
    const wrapper = await mountView()
    await buttonByText(wrapper, '编辑').trigger('click')
    await flushPromises()

    // el-form-item 在测试里是未解析的自定义元素，label 只存在于 props，
    // 因此这里断言「说明文案」与开关本身，而不是标签文字。
    expect(wrapper.text()).toContain('开启后模型可自行调用检索工具')
    // 工具调用开关有独立 class，避免和「启用/视觉/深度思考」混淆
    const toolSwitch = wrapper.find('.tool-call-switch')
    expect(toolSwitch.exists()).toBe(true)
    // 回填值来自后端 toolCallEnabled=true
    expect(toolSwitch.attributes('data-on')).toBe('true')
  })

  it('保存时原样回传 toolCallEnabled（不会静默关掉已开启的模型）', async () => {
    const wrapper = await mountView()
    await buttonByText(wrapper, '编辑').trigger('click')
    await flushPromises()
    await buttonByText(wrapper, '保存').trigger('click')
    await flushPromises()

    expect(mocks.updateAdminAiModel).toHaveBeenCalledTimes(1)
    const [id, payload] = mocks.updateAdminAiModel.mock.calls[0]
    expect(id).toBe(MODEL.id)
    expect(payload.toolCallEnabled).toBe(true)
    // 其它能力字段同样不能丢
    expect(payload.visionEnabled).toBe(true)
    expect(payload.reasoningEnabled).toBe(true)
  })

  it('关闭开关后保存回传 false（双向可控）', async () => {
    const wrapper = await mountView()
    await buttonByText(wrapper, '编辑').trigger('click')
    await flushPromises()

    await wrapper.find('.tool-call-switch').trigger('click')
    await flushPromises()
    await buttonByText(wrapper, '保存').trigger('click')
    await flushPromises()

    const [, payload] = mocks.updateAdminAiModel.mock.calls[0]
    expect(payload.toolCallEnabled).toBe(false)
  })
})
