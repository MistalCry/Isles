import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'

// 搜索联想竞态（docs/13 P1-11）：连打几个字时，先发的请求可能后到。
// 只认最后一次查询的结果，`suggesting` 也只由最后一次收尾。
const mocks = vi.hoisted(() => ({
  getSearchSuggestion: vi.fn(),
  getHotKeywords: vi.fn(),
}))

vi.mock('@/api', () => ({
  searchApi: {
    getSearchSuggestion: mocks.getSearchSuggestion,
    getHotKeywords: mocks.getHotKeywords,
  },
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isAuthenticated: false, currentUser: null }),
}))

import SearchPanel from '@/components/SearchPanel.vue'

const SuggestionListStub = defineComponent({
  name: 'UserAvatar',
  props: { src: { type: String, default: null }, name: { type: String, default: '' } },
  template: '<span class="avatar-stub">{{ name }}</span>',
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}

async function mountPanel() {
  const wrapper = mount(SearchPanel, {
    props: { scope: 'global' },
    global: {
      stubs: { UserAvatar: SuggestionListStub },
    },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.useFakeTimers()
  mocks.getHotKeywords.mockResolvedValue([])
  mocks.getSearchSuggestion.mockResolvedValue({ channels: [], keywords: [] })
  window.localStorage.clear()
})

describe('SearchPanel 联想竞态', () => {
  it('旧查询后到时不覆盖新查询的联想结果', async () => {
    const first = deferred<{ channels: unknown[]; keywords: string[] }>()
    const second = deferred<{ channels: unknown[]; keywords: string[] }>()
    mocks.getSearchSuggestion.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)

    const wrapper = await mountPanel()
    const input = wrapper.find('input')

    await input.setValue('a')
    vi.advanceTimersByTime(300)
    await flushPromises()

    await input.setValue('ab')
    vi.advanceTimersByTime(300)
    await flushPromises()

    // 乱序：先回新查询，再回旧查询
    second.resolve({ channels: [], keywords: ['ab 的结果'] })
    await flushPromises()
    expect(wrapper.text()).toContain('ab 的结果')

    first.resolve({ channels: [], keywords: ['a 的结果'] })
    await flushPromises()
    expect(wrapper.text()).toContain('ab 的结果')
    expect(wrapper.text()).not.toContain('a 的结果')
  })

  it('清空输入会让在途联想作废，且不残留「搜索中」状态', async () => {
    const pending = deferred<{ channels: unknown[]; keywords: string[] }>()
    mocks.getSearchSuggestion.mockReturnValueOnce(pending.promise)

    const wrapper = await mountPanel()
    const input = wrapper.find('input')

    await input.setValue('红石')
    vi.advanceTimersByTime(300)
    await flushPromises()

    await input.setValue('')
    await flushPromises()

    pending.resolve({ channels: [], keywords: ['红石的结果'] })
    await flushPromises()
    expect(wrapper.text()).not.toContain('红石的结果')
  })
})
