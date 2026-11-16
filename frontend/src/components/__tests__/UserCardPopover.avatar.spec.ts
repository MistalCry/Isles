import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

/**
 * 用户卡片头像（第 18 轮契约）：代理创建时带的 avatarUrl 要一路透到前台卡片。
 * 这里锁三件事：
 *   1. props.user 上有 avatarUrl → 卡片头部渲染 <img>（不是首字兜底）；
 *   2. 没有 avatarUrl → 走首字兜底，不渲染坏图；
 *   3. /users/{id}/card 返回的头像优先于列表里的旧值（改头像后卡片能刷新）。
 */

const mocks = vi.hoisted(() => ({
  getUserCard: vi.fn(),
  sendFriendRequest: vi.fn(),
}))

vi.mock('@/api', () => ({
  usersApi: { getUserCard: mocks.getUserCard },
  friendsApi: { sendFriendRequest: mocks.sendFriendRequest },
}))

import UserCardPopover from '@/components/UserCardPopover.vue'

/** el-popover 换成会转发 show 事件的桩，才能触发卡片懒加载 */
const PopoverStub = {
  name: 'ElPopover',
  props: ['trigger', 'width', 'placement'],
  emits: ['show'],
  template: '<div class="popover-stub"><slot name="reference" /><div class="popover-body"><slot /></div></div>',
}

function mountCard(user: Record<string, unknown>) {
  return mount(UserCardPopover, {
    props: { user: user as never },
    slots: { default: '<span class="trigger">作者</span>' },
    global: {
      stubs: {
        'el-popover': PopoverStub,
        // AiBadge 内部用了 el-tooltip，测试里给个透明桩，避免「组件未解析」告警
        'el-tooltip': { template: '<span><slot /></span>' },
      },
    },
  })
}

function avatarImg(wrapper: ReturnType<typeof mountCard>) {
  return wrapper.find('.user-card__head img')
}

describe('UserCardPopover 头像', () => {
  beforeEach(() => {
    mocks.getUserCard.mockReset()
    mocks.sendFriendRequest.mockReset()
  })

  it('用户自带 avatarUrl 时渲染头像图片', () => {
    const wrapper = mountCard({
      id: 'u1',
      username: 'agent_demo',
      nickname: '研究代理',
      avatarUrl: '/uploads/default-avatar.png',
      isAgent: true,
    })

    const img = avatarImg(wrapper)
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('/uploads/default-avatar.png')
    // 有头像就不该再出现首字兜底
    expect(wrapper.find('.user-avatar__fallback').exists()).toBe(false)
  })

  it('没有 avatarUrl 时回落到首字，不渲染坏图', () => {
    const wrapper = mountCard({ id: 'u2', username: 'plain', nickname: '无头像用户' })

    expect(avatarImg(wrapper).exists()).toBe(false)
    expect(wrapper.find('.user-avatar__fallback').text()).toBe('无')
  })

  it('卡片接口返回的头像优先于列表旧值（改头像后能刷新）', async () => {
    mocks.getUserCard.mockResolvedValue({
      id: 'u3',
      username: 'agent_new',
      nickname: '新头像代理',
      avatarUrl: '/uploads/new-avatar.png',
      isAgent: true,
    })

    const wrapper = mountCard({
      id: 'u3',
      username: 'agent_new',
      nickname: '新头像代理',
      avatarUrl: '/uploads/old-avatar.png',
      isAgent: true,
    })

    await wrapper.findComponent({ name: 'ElPopover' }).vm.$emit('show')
    await flushPromises()

    expect(mocks.getUserCard).toHaveBeenCalledWith('u3', undefined)
    expect(avatarImg(wrapper).attributes('src')).toBe('/uploads/new-avatar.png')
  })
})
