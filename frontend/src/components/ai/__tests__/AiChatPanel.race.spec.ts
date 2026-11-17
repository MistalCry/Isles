/* eslint-disable vue/one-component-per-file */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'

// 切会话竞态（docs/13 P0-4）：连点两个会话时，先发的请求可能后到。
// 若不做序号判断，会出现「标题是 B、正文是 A」。
const mocks = vi.hoisted(() => ({
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
  createConversation: vi.fn(),
  streamChat: vi.fn(),
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

const MessageStub = defineComponent({
  name: 'AiMessageItem',
  props: { message: { type: Object, required: true }, streaming: { type: Boolean, default: false } },
  template: '<div class="msg-stub">{{ message.content }}</div>',
})

/** 侧栏桩：暴露一个 emit 入口，测试里用它模拟「点了某个会话」 */
const SidebarStub = defineComponent({
  name: 'AiSessionSidebar',
  props: {
    conversations: { type: Array, default: () => [] },
    activeId: { type: String, default: null },
  },
  emits: ['select', 'create', 'rename', 'remove', 'retry'],
  template: '<div class="sidebar-stub" :data-active="activeId" />',
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

const CONVERSATIONS = [
  { id: 'c1', title: '会话一', scope: 'GLOBAL' },
  { id: 'c2', title: '会话二', scope: 'GLOBAL' },
]

let messageCalls: Record<string, ReturnType<typeof deferred<unknown>>>

function messagesOf(conversationId: string) {
  return {
    items: [
      {
        id: `m-${conversationId}`,
        conversationId,
        role: 'ASSISTANT',
        content: `${conversationId} 的消息`,
        status: 'DONE',
      },
    ],
    nextCursor: null,
  }
}

async function mountPanel() {
  const wrapper = mount(AiChatPanel, {
    props: { scope: 'GLOBAL' },
    global: {
      stubs: {
        AiMessageItem: MessageStub,
        AiSessionSidebar: SidebarStub,
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

beforeEach(() => {
  setActivePinia(createPinia())
  messageCalls = { c1: deferred(), c2: deferred() }
  mocks.getAiStatus.mockResolvedValue({ configured: true, message: null })
  mocks.listAiModels.mockResolvedValue([])
  mocks.listConversations.mockResolvedValue({ items: CONVERSATIONS, nextCursor: null })
  mocks.listMessages.mockImplementation((conversationId: string) => {
    if (!messageCalls[conversationId]) messageCalls[conversationId] = deferred()
    return messageCalls[conversationId].promise
  })
})

describe('AiChatPanel 切会话竞态', () => {
  it('先点 B 再回来 A 的响应，不会覆盖 B 的消息', async () => {
    const wrapper = await mountPanel()
    // 挂载时会自动选中第一个会话 c1，请求悬着

    const sidebar = wrapper.findComponent(SidebarStub)
    sidebar.vm.$emit('select', 'c2')
    await flushPromises()

    // 乱序：先回 c2，再回 c1
    messageCalls.c2.resolve(messagesOf('c2'))
    await flushPromises()
    expect(wrapper.text()).toContain('c2 的消息')

    messageCalls.c1.resolve(messagesOf('c1'))
    await flushPromises()
    expect(wrapper.text()).toContain('c2 的消息')
    expect(wrapper.text()).not.toContain('c1 的消息')
  })

  it('messageLoading 只由最后一次请求收尾', async () => {
    const wrapper = await mountPanel()
    const sidebar = wrapper.findComponent(SidebarStub)
    sidebar.vm.$emit('select', 'c2')
    await flushPromises()

    // c2 先回来：加载态结束，且不应被 c1 的迟到响应重新打开
    messageCalls.c2.resolve(messagesOf('c2'))
    await flushPromises()
    messageCalls.c1.resolve(messagesOf('c1'))
    await flushPromises()

    expect(wrapper.text()).toContain('c2 的消息')
    expect(wrapper.find('.msg-stub').exists()).toBe(true)
  })
})
