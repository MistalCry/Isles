import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import MentionPicker from '@/components/MentionPicker.vue'

const getMentionCandidates = vi.fn()
vi.mock('@/api', () => ({
  channelsApi: {
    getMentionCandidates: (...args: unknown[]) => getMentionCandidates(...args),
  },
}))

describe('MentionPicker', () => {
  it('选择成员时抛出 id + 昵称（ID 化提及：重名也能精确命中）', async () => {
    getMentionCandidates.mockResolvedValueOnce([
      { id: '01USERAAAAAAAAAAAAAAAAAA', username: 'isles', nickname: '岛民甲', avatarUrl: '' },
      { id: '01USERBBBBBBBBBBBBBBBBBB', username: 'isles2', nickname: '岛民甲', avatarUrl: '' },
    ])

    const wrapper = mount(MentionPicker, { props: { channelId: '01CHAN0000000000000000000' } })

    // 打开浮层 → 触发加载候选
    await wrapper.find('.el-popover-stub__reference').trigger('click')
    await flushPromises()

    const items = wrapper.findAll('.mention-picker__item')
    expect(items).toHaveLength(2)

    await items[1].trigger('click')
    // 重名的两个人 id 不同，选择第二个必须给出第二个的 id
    expect(wrapper.emitted('select')?.[0]?.[0]).toEqual({
      id: '01USERBBBBBBBBBBBBBBBBBB',
      name: '岛民甲',
    })
  })

  it('没有频道时不请求候选，避免无意义请求', async () => {
    getMentionCandidates.mockClear()
    const wrapper = mount(MentionPicker, { props: {} })
    await wrapper.find('.el-popover-stub__reference').trigger('click')
    await flushPromises()
    expect(getMentionCandidates).not.toHaveBeenCalled()
    expect(wrapper.findAll('.mention-picker__item')).toHaveLength(0)
  })

  it('按关键词过滤候选', async () => {
    getMentionCandidates.mockResolvedValueOnce([
      { id: '01USERAAAAAAAAAAAAAAAAAA', username: 'alice', nickname: 'Alice', avatarUrl: '' },
      { id: '01USERBBBBBBBBBBBBBBBBBB', username: 'bob', nickname: 'Bob', avatarUrl: '' },
    ])

    const wrapper = mount(MentionPicker, { props: { channelId: '01CHAN0000000000000000000' } })
    await wrapper.find('.el-popover-stub__reference').trigger('click')
    await flushPromises()

    await wrapper.find('.el-input-stub').setValue('bo')
    await flushPromises()

    const items = wrapper.findAll('.mention-picker__item')
    expect(items).toHaveLength(1)
    expect(items[0].text()).toContain('Bob')
  })
})
