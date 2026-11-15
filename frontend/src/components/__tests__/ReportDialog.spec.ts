import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { setLocale } from '@/i18n'
import i18n from '@/i18n'

// 回归用例（用户实测 bug）：举报原因必须提交**代码**（SPAM/ABUSE/…），
// 不能把界面文案（「垃圾广告」/「Spam」）当 reason 发给后端——后端 @Pattern 只认代码，
// 一旦发文案就是 400，中英文界面都会挂。这里按语言逐个锁死。
const mocks = vi.hoisted(() => ({
  submitReport: vi.fn(),
}))

vi.mock('@/api', () => ({
  manageApi: { submitReport: mocks.submitReport },
}))

import ReportDialog from '@/components/ReportDialog.vue'

const stubs = {
  'el-dialog': {
    props: ['modelValue'],
    template: '<div class="el-dialog-stub" v-if="modelValue"><slot /><slot name="footer" /></div>',
  },
  'el-radio-group': {
    name: 'ElRadioGroup',
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<div class="el-radio-group-stub" :data-value="modelValue"><slot /></div>',
  },
  'el-radio': {
    props: ['value'],
    template: '<label class="el-radio-stub" :data-value="value"><slot /></label>',
  },
}

/** 后端 ReportCreateRequest.reason 的白名单（@Pattern(SPAM|ABUSE|PORN|ILLEGAL|OTHER)） */
const BACKEND_REASON_CODES = ['SPAM', 'ABUSE', 'PORN', 'ILLEGAL', 'OTHER']

function mountDialog(props: Partial<{ targetType: 'post' | 'comment'; targetId: string }> = {}) {
  return mount(ReportDialog, {
    props: {
      modelValue: true,
      targetType: 'post',
      targetId: '01POST0000000000000000000',
      ...props,
    },
    global: { stubs },
  })
}

async function pickReason(wrapper: ReturnType<typeof mountDialog>, code: string) {
  const group = wrapper.findComponent({ name: 'ElRadioGroup' })
  group.vm.$emit('update:modelValue', code)
  await flushPromises()
}

async function submit(wrapper: ReturnType<typeof mountDialog>) {
  const button = wrapper.findAll('button').find((item) => item.text().includes(i18n.global.t('report.submit')))
  if (!button) throw new Error('未找到提交按钮')
  await button.trigger('click')
  await flushPromises()
}

describe('ReportDialog 举报原因提交', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.submitReport.mockResolvedValue(undefined)
  })

  afterEach(() => {
    // 语言是全局状态，用例结束必须还原，避免污染其它测试文件
    setLocale('zh-CN')
  })

  it('原因代码与后端白名单完全一致（新增原因必须先改这里和后端）', async () => {
    const { REPORT_REASON_CODES } = await import('@/constants/app')
    expect([...REPORT_REASON_CODES]).toEqual(BACKEND_REASON_CODES)
  })

  it('中文界面：默认提交 SPAM 代码，而不是「垃圾广告」文案', async () => {
    setLocale('zh-CN')
    const wrapper = mountDialog()
    // 界面上展示的是中文文案
    expect(wrapper.text()).toContain(i18n.global.t('report.reason.spam'))

    await submit(wrapper)

    expect(mocks.submitReport).toHaveBeenCalledTimes(1)
    const payload = mocks.submitReport.mock.calls[0][0]
    expect(payload.reason).toBe('SPAM')
    expect(payload.reason).not.toBe(i18n.global.t('report.reason.spam'))
    expect(BACKEND_REASON_CODES).toContain(payload.reason)
  })

  it('英文界面：默认提交的仍是代码，不是英文文案', async () => {
    setLocale('en-US')
    const wrapper = mountDialog()
    expect(wrapper.text()).toContain(i18n.global.t('report.reason.spam'))

    await submit(wrapper)

    const payload = mocks.submitReport.mock.calls[0][0]
    expect(payload.reason).toBe('SPAM')
    expect(payload.reason).not.toBe(i18n.global.t('report.reason.spam'))
  })

  it('中英文界面下五种原因都能提交，且 payload.reason 恒为代码', async () => {
    for (const locale of ['zh-CN', 'en-US'] as const) {
      setLocale(locale)
      for (const code of BACKEND_REASON_CODES) {
        mocks.submitReport.mockClear()
        const wrapper = mountDialog()
        await pickReason(wrapper, code)
        await submit(wrapper)
        const payload = mocks.submitReport.mock.calls[0]?.[0]
        expect(payload, `${locale} 下 ${code} 未提交`).toBeTruthy()
        expect(payload.reason, `${locale} 下提交了非代码值`).toBe(code)
      }
    }
  })

  it('帖子与评论分别提交 POST / COMMENT', async () => {
    setLocale('zh-CN')
    const postDialog = mountDialog({ targetType: 'post', targetId: '01POST0000000000000000001' })
    await submit(postDialog)
    expect(mocks.submitReport.mock.calls[0][0]).toMatchObject({
      targetType: 'POST',
      targetId: '01POST0000000000000000001',
    })

    mocks.submitReport.mockClear()
    const commentDialog = mountDialog({ targetType: 'comment', targetId: '01COMMENT000000000000001' })
    await submit(commentDialog)
    expect(mocks.submitReport.mock.calls[0][0]).toMatchObject({
      targetType: 'COMMENT',
      targetId: '01COMMENT000000000000001',
    })
  })

  it('补充说明去掉首尾空白；留空时不下发 description', async () => {
    setLocale('zh-CN')
    const wrapper = mountDialog()
    await wrapper.find('.el-input-stub').setValue('  广告刷屏  ')
    await submit(wrapper)
    expect(mocks.submitReport.mock.calls[0][0].description).toBe('广告刷屏')

    mocks.submitReport.mockClear()
    const blank = mountDialog()
    await blank.find('.el-input-stub').setValue('   ')
    await submit(blank)
    expect(mocks.submitReport.mock.calls[0][0].description).toBeUndefined()
  })

  it('提交失败时弹窗不关闭，并展示错误信息', async () => {
    setLocale('zh-CN')
    mocks.submitReport.mockRejectedValue(new Error('网络开小差了'))
    const wrapper = mountDialog()
    await submit(wrapper)
    expect(wrapper.find('.el-dialog-stub').exists()).toBe(true)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
})
