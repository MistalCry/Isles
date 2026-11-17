import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'

/**
 * 上传进度回归：媒体上传必须把百分比透到按钮上，失败要能重试。
 * 进度条这类「有回调但没人用」的坑（onProgress 零调用）只能靠测试盯住。
 */
const mocks = vi.hoisted(() => ({
  uploadMedia: vi.fn(),
  validateMediaFile: vi.fn(() => null),
  isPreviewUnsupported: vi.fn(() => false),
  createChannelPost: vi.fn(),
}))

vi.mock('@/api', () => ({
  channelsApi: { getBoards: vi.fn().mockResolvedValue([]), createChannelPost: mocks.createChannelPost },
  mediaApi: {
    uploadMedia: mocks.uploadMedia,
    validateMediaFile: mocks.validateMediaFile,
    isPreviewUnsupported: mocks.isPreviewUnsupported,
  },
}))

import PostComposer from '@/components/PostComposer.vue'

const UPLOADED = {
  id: '01MEDIA000000000000000001',
  type: 'IMAGE' as const,
  url: '/uploads/2026/11/x.png',
  thumbUrl: null,
}

function fileInput(wrapper: ReturnType<typeof mount>) {
  const input = wrapper.find('input[type="file"]')
  if (!input.exists()) throw new Error('未找到文件选择框')
  return input
}

async function pickFile(wrapper: ReturnType<typeof mount>, file: File) {
  const input = fileInput(wrapper).element as HTMLInputElement
  Object.defineProperty(input, 'files', { value: [file], configurable: true })
  await fileInput(wrapper).trigger('change')
  await flushPromises()
}

describe('PostComposer 上传进度', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.validateMediaFile.mockReturnValue(null)
    mocks.isPreviewUnsupported.mockReturnValue(false)
  })

  it('上传中显示百分比，完成后进度归零并出现在媒体列表', async () => {
    // 用对象持有 resolve，避免 TS 对闭包赋值的控制流收窄（会判成 never）
    const pending: { resolve?: () => void } = {}
    mocks.uploadMedia.mockImplementation((_file: File, onProgress?: (percent: number) => void) => {
      onProgress?.(42)
      return new Promise((resolve) => {
        pending.resolve = () => resolve(UPLOADED)
      })
    })

    const wrapper = mount(PostComposer, { props: { channelId: '01CHAN0000000000000000000' } })
    await flushPromises()
    await pickFile(wrapper, new File(['x'], 'a.png', { type: 'image/png' }))

    const button = wrapper.findAll('button').find((item) => item.text().includes('42%'))
    expect(button, '按钮上应出现 42% 进度').toBeTruthy()

    pending.resolve?.()
    await flushPromises()
    expect(wrapper.findAll('.post-composer__media-item')).toHaveLength(1)
    expect(wrapper.text()).not.toContain('42%')
  })

  it('上传失败时出现「重试上传」，点击后重新上传同一文件', async () => {
    mocks.uploadMedia.mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce(UPLOADED)

    const wrapper = mount(PostComposer, { props: { channelId: '01CHAN0000000000000000000' } })
    await flushPromises()
    await pickFile(wrapper, new File(['x'], 'b.png', { type: 'image/png' }))

    const retry = wrapper.findAll('button').find((item) => item.text().includes('重试上传'))
    expect(retry, '失败后应出现重试按钮').toBeTruthy()

    await retry!.trigger('click')
    await flushPromises()
    expect(mocks.uploadMedia).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.post-composer__media-item')).toHaveLength(1)
  })

  it('上传中不能发布：按钮禁用，且 submit 直接拦下不发请求', async () => {
    // 用户实测：选图后立刻点发布，帖子发出去了但图没了
    const pending: { resolve?: () => void } = {}
    mocks.uploadMedia.mockImplementation(
      () =>
        new Promise((resolve) => {
          pending.resolve = () => resolve(UPLOADED)
        }),
    )
    mocks.createChannelPost.mockResolvedValue({ id: 'p1', title: '正文' })

    const wrapper = mount(PostComposer, { props: { channelId: '01CHAN0000000000000000000' } })
    await flushPromises()
    await wrapper.find('.el-input-stub').setValue('正文')
    await pickFile(wrapper, new File(['x'], 'c.png', { type: 'image/png' }))

    const publish = wrapper.findAll('button').find((item) => item.text().trim() === '发布')
    expect(publish, '应能找到发布按钮').toBeTruthy()
    expect(publish!.attributes('disabled'), '上传中发布按钮必须禁用').toBeDefined()

    // 即便按钮被强行点击（或回车提交），也不能发出「没有图」的帖子
    await publish!.trigger('click')
    await flushPromises()
    expect(mocks.createChannelPost).not.toHaveBeenCalled()

    pending.resolve?.()
    await flushPromises()
    const afterUpload = wrapper.findAll('button').find((item) => item.text().trim() === '发布')
    expect(afterUpload!.attributes('disabled')).toBeUndefined()
  })
})
