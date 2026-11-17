/* eslint-disable vue/one-component-per-file */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, reactive } from 'vue'

// 切频道竞态（docs/13 P0-3）：慢网下从 A 切到 B，A 的响应可能后到。
// 页面必须整包丢弃过期响应，否则出现「URL 是 B、内容是 A」。
const mocks = vi.hoisted(() => ({
  getChannel: vi.fn(),
  getPinnedPosts: vi.fn(),
  getBoards: vi.fn(),
  getChannelPosts: vi.fn(),
}))

const routeState = reactive({ params: { id: 'A' } as Record<string, string> })

vi.mock('vue-router', () => ({
  useRoute: () => routeState,
  useRouter: () => ({
    push: vi.fn(),
    replace: vi.fn(),
    currentRoute: { value: { fullPath: '/channels/A' } },
  }),
}))

vi.mock('@/api', () => ({
  channelsApi: {
    getChannel: mocks.getChannel,
    getPinnedPosts: mocks.getPinnedPosts,
    getBoards: mocks.getBoards,
    getChannelPosts: mocks.getChannelPosts,
    joinChannel: vi.fn(),
    leaveChannel: vi.fn(),
  },
}))

vi.mock('@/api/apps', () => ({ listChannelApps: vi.fn().mockResolvedValue([]) }))
vi.mock('@/api/activities', () => ({
  listChannelActivities: vi.fn().mockResolvedValue([]),
  signupActivity: vi.fn(),
  cancelActivitySignup: vi.fn(),
}))

import ChannelView from '@/pages/ChannelView.vue'

/** 只渲染频道名与置顶帖标题，便于断言「当前到底是哪个频道的数据」 */
const ChannelHeaderStub = defineComponent({
  name: 'ChannelHeader',
  props: { channel: { type: Object, default: null } },
  template: '<div class="channel-header-stub">{{ channel?.name }}</div>',
})

const PostCardStub = defineComponent({
  name: 'PostCard',
  props: { post: { type: Object, default: null } },
  template: '<div class="post-card-stub">{{ post?.title }}</div>',
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

function channel(id: string, name: string) {
  return { id, name, description: '', isJoined: false, memberCount: 0 }
}

let channelCalls: Record<string, ReturnType<typeof deferred<unknown>>>
let pinnedCalls: Record<string, ReturnType<typeof deferred<unknown>>>

async function mountView() {
  const wrapper = mount(ChannelView, {
    global: {
      stubs: {
        ChannelHeader: ChannelHeaderStub,
        PostCard: PostCardStub,
        PostComposer: true,
        SearchPanel: true,
        ChannelAssistantPanel: true,
        ChannelSettingsDialog: true,
      },
    },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  routeState.params.id = 'A'
  channelCalls = { A: deferred(), B: deferred() }
  pinnedCalls = { A: deferred(), B: deferred() }
  mocks.getChannel.mockImplementation((id: string) => {
    if (!channelCalls[id]) channelCalls[id] = deferred()
    return channelCalls[id].promise
  })
  mocks.getPinnedPosts.mockImplementation((id: string) => {
    if (!pinnedCalls[id]) pinnedCalls[id] = deferred()
    return pinnedCalls[id].promise
  })
  mocks.getBoards.mockResolvedValue([])
  mocks.getChannelPosts.mockResolvedValue({ items: [], nextCursor: null })
})

describe('ChannelView 切频道竞态', () => {
  it('A 的响应后到时不会覆盖 B 的频道信息', async () => {
    const wrapper = await mountView()

    // 切到 B（A 的请求还悬着）
    routeState.params.id = 'B'
    await flushPromises()

    // 乱序返回：先 B，后 A
    channelCalls.B.resolve(channel('B', 'B 频道'))
    await flushPromises()
    expect(wrapper.text()).toContain('B 频道')

    channelCalls.A.resolve(channel('A', 'A 频道'))
    await flushPromises()
    expect(wrapper.text()).toContain('B 频道')
    expect(wrapper.text()).not.toContain('A 频道')
  })

  it('置顶帖同样只认最后一次请求', async () => {
    const wrapper = await mountView()

    routeState.params.id = 'B'
    await flushPromises()

    channelCalls.B.resolve(channel('B', 'B 频道'))
    await flushPromises()

    pinnedCalls.B.resolve([{ id: 'p-b', title: 'B 的置顶' }])
    await flushPromises()
    expect(wrapper.text()).toContain('B 的置顶')

    pinnedCalls.A.resolve([{ id: 'p-a', title: 'A 的置顶' }])
    await flushPromises()
    expect(wrapper.text()).toContain('B 的置顶')
    expect(wrapper.text()).not.toContain('A 的置顶')
  })
})
