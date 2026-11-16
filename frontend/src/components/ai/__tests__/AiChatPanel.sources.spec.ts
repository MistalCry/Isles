import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'

// 来源引用回归（用户实测）：工具模式下后端先推 meta（关键词兜底来源），检索完成后再推 sources（真实命中）。
// 前端必须让 sources 事件「整体替换」meta 里的兜底来源；只有没收到 sources 事件时（关闭工具调用）才回落到 meta。
const mocks = vi.hoisted(() => ({
  streamChat: vi.fn(),
  createConversation: vi.fn(),
  listConversations: vi.fn(),
  listMessages: vi.fn(),
  getAiStatus: vi.fn(),
  listAiModels: vi.fn(),
}))

vi.mock('@/api/ai', () => ({
  getAiStatus: mocks.getAiStatus,
  listAiModels: mocks.listAiModels,
  listConversations: mocks.listConversations,
  listMessages: mocks.listMessages,
  createConversation: mocks.createConversation,
  streamChat: mocks.streamChat,
  stopMessage: vi.fn(),
  deleteConversation: vi.fn(),
  renameConversation: vi.fn(),
  regenerateChat: vi.fn(),
  editAndResend: vi.fn(),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isAuthenticated: true }),
}))

import AiChatPanel from '@/components/ai/AiChatPanel.vue'

const META_SOURCES = [{ postId: '01POSTMETA0000000000000000', title: '关键词兜底来源' }]
const TOOL_SOURCES = [{ postId: '01POSTTOOL0000000000000000', title: '工具真实命中来源' }]

/** 把消息对象上的 sources 标题渲染出来，便于断言「界面最终用了哪批来源」 */
const AiMessageItemStub = defineComponent({
  name: 'AiMessageItem',
  props: ['message', 'streaming'],
  template: `<div class="msg-stub" :data-role="message.role">
    <span class="msg-stub__content">{{ message.content }}</span>
    <span class="msg-stub__sources">{{ (message.sources || []).map((s) => s.title).join('|') }}</span>
  </div>`,
})

function baseMocks() {
  mocks.getAiStatus.mockResolvedValue({ configured: true, message: null })
  mocks.listAiModels.mockResolvedValue([
    {
      id: '01MODEL000000000000000000',
      name: 'mock',
      model: 'mock-chat',
      enabled: true,
      isDefault: true,
      visionEnabled: false,
      reasoningEnabled: false,
    },
  ])
  mocks.listConversations.mockResolvedValue({ items: [], nextCursor: null })
  mocks.createConversation.mockResolvedValue({ id: '01CONV0000000000000000000', scope: 'GLOBAL' })
  mocks.listMessages.mockResolvedValue({ items: [], nextCursor: null })
}

async function mountPanel() {
  const wrapper = mount(AiChatPanel, {
    props: { scope: 'GLOBAL' },
    global: {
      stubs: {
        AiMessageItem: AiMessageItemStub,
        AiSessionSidebar: true,
        'el-scrollbar': { template: '<div><slot /></div>' },
        'el-drawer': { template: '<div><slot /></div>' },
        'el-dropdown': { template: '<div><slot /></div>' },
        'el-dropdown-menu': { template: '<div><slot /></div>' },
        'el-dropdown-item': { template: '<div><slot /></div>' },
        'el-tooltip': { template: '<div><slot /></div>' },
        'el-select': { template: '<div><slot /></div>' },
        'el-option': { template: '<span />' },
        'el-empty': { template: '<div />' },
        'el-skeleton': { template: '<div />' },
      },
    },
  })
  await flushPromises()
  return wrapper
}

/** 输入内容并点发送，触发一次流式问答 */
async function ask(wrapper: Awaited<ReturnType<typeof mountPanel>>, text: string) {
  await wrapper.find('.el-input-stub').setValue(text)
  const send = wrapper.findAll('button').find((item) => item.text().includes('发送'))
  if (!send) throw new Error('未找到发送按钮')
  await send.trigger('click')
  await flushPromises()
}

function sourcesText(wrapper: Awaited<ReturnType<typeof mountPanel>>): string {
  return wrapper.findAll('.msg-stub__sources').map((item) => item.text()).join('||')
}

describe('AiChatPanel 来源引用', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    localStorage.clear()
    baseMocks()
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('工具模式：sources 事件整体替换 meta 兜底来源', async () => {
    mocks.streamChat.mockImplementation(async (_id, _payload, handlers) => {
      handlers.onMeta({
        conversationId: '01CONV0000000000000000000',
        messageId: '01MSG0000000000000000000',
        model: 'mock-chat',
        sources: META_SOURCES,
        deepThinking: false,
      })
      handlers.onSources?.({ sources: TOOL_SOURCES, toolRounds: 1 })
      handlers.onDelta('回答正文')
      handlers.onDone({ messageId: '01MSG0000000000000000000', aborted: false, status: 'COMPLETED' })
    })

    const wrapper = await mountPanel()
    await ask(wrapper, '工具模式提问')

    const text = sourcesText(wrapper)
    expect(text).toContain('工具真实命中来源')
    expect(text).not.toContain('关键词兜底来源')
  })

  it('正例：完全没收到 sources 事件时，回填 meta 的关键词来源', async () => {
    mocks.streamChat.mockImplementation(async (_id, _payload, handlers) => {
      handlers.onMeta({
        conversationId: '01CONV0000000000000000000',
        messageId: '01MSG0000000000000000001',
        model: 'mock-chat',
        sources: META_SOURCES,
        deepThinking: false,
      })
      handlers.onDelta('回答正文')
      handlers.onDone({ messageId: '01MSG0000000000000000001', aborted: false, status: 'COMPLETED' })
    })

    const wrapper = await mountPanel()
    await ask(wrapper, '非工具模式提问')

    expect(sourcesText(wrapper)).toContain('关键词兜底来源')
  })

  it('没收到 sources 事件且 meta 也没有来源：保持空，不凭空造引用', async () => {
    mocks.streamChat.mockImplementation(async (_id, _payload, handlers) => {
      handlers.onMeta({
        conversationId: '01CONV0000000000000000000',
        messageId: '01MSG0000000000000000003',
        model: 'mock-chat',
        sources: [],
        deepThinking: false,
      })
      handlers.onDelta('回答正文')
      handlers.onDone({ messageId: '01MSG0000000000000000003', aborted: false, status: 'COMPLETED' })
    })

    const wrapper = await mountPanel()
    await ask(wrapper, '没有任何来源的提问')

    // 用户消息也渲染了 sources 容器，这里逐条断言都为空
    const nodes = wrapper.findAll('.msg-stub__sources')
    expect(nodes.length).toBeGreaterThan(0)
    expect(nodes.every((node) => node.text() === '')).toBe(true)
  })

  // D2 已修：面板记录「是否收到过 sources 事件」，收到即以其为准（哪怕空数组），
  // 只有完全没收到事件时才回填 meta 的关键词兜底。
  it('0 命中（sources 事件为空数组）：不应回填 meta 兜底来源', async () => {
    mocks.streamChat.mockImplementation(async (_id, _payload, handlers) => {
      handlers.onMeta({
        conversationId: '01CONV0000000000000000000',
        messageId: '01MSG0000000000000000002',
        model: 'mock-chat',
        sources: META_SOURCES,
        deepThinking: false,
      })
      handlers.onSources?.({ sources: [], toolRounds: 1 })
      handlers.onDelta('没有找到相关帖子')
      handlers.onDone({ messageId: '01MSG0000000000000000002', aborted: false, status: 'COMPLETED' })
    })

    const wrapper = await mountPanel()
    await ask(wrapper, '没有命中任何帖子的提问')

    expect(sourcesText(wrapper)).not.toContain('关键词兜底来源')
  })
})
