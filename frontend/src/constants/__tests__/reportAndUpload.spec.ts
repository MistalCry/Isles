import { describe, expect, it } from 'vitest'
import { IMAGE_ACCEPT, MEDIA_ACCEPT, REPORT_REASON_CODES, VIDEO_ACCEPT, reportReasonLabel } from '@/constants/app'
import { detectMediaType, isPreviewUnsupported, uploadTimeoutMs, validateMediaFile } from '@/api/media'
import { setLocale } from '@/i18n'

/** 构造 File 只需要 name/type/size，测试环境不必真读内容 */
function fakeFile(name: string, type: string, size = 1024): File {
  return new File([new Uint8Array(size)], name, { type })
}

describe('举报原因代码（与后端 @Pattern 对齐）', () => {
  it('代码集合与后端 ReportCreateRequest 完全一致', () => {
    // 后端：@Pattern(regexp = "SPAM|ABUSE|PORN|ILLEGAL|OTHER")
    expect([...REPORT_REASON_CODES]).toEqual(['SPAM', 'ABUSE', 'PORN', 'ILLEGAL', 'OTHER'])
  })

  it('提交用代码、展示用文案：中文', () => {
    setLocale('zh-CN')
    expect(reportReasonLabel('SPAM')).toBe('垃圾广告')
    expect(reportReasonLabel('ABUSE')).toBe('人身攻击')
    expect(reportReasonLabel('PORN')).toBe('色情低俗')
    expect(reportReasonLabel('ILLEGAL')).toBe('违法违规')
    expect(reportReasonLabel('OTHER')).toBe('其他')
  })

  it('英文界面同样是文案而不是代码', () => {
    setLocale('en-US')
    expect(reportReasonLabel('SPAM')).toBe('Spam')
    expect(reportReasonLabel('ILLEGAL')).toBe('Illegal content')
    setLocale('zh-CN')
  })

  it('未知代码原样返回，不渲染空白', () => {
    expect(reportReasonLabel('SOMETHING_ELSE')).toBe('SOMETHING_ELSE')
    expect(reportReasonLabel(null)).toBe('')
  })
})

describe('上传格式（accept 与校验）', () => {
  it('accept 覆盖产品声明的图片/视频格式', () => {
    for (const type of ['image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/avif', 'image/bmp', 'image/heic', 'image/heif']) {
      expect(IMAGE_ACCEPT).toContain(type)
    }
    for (const type of ['video/mp4', 'video/webm', 'video/quicktime', 'video/x-m4v']) {
      expect(VIDEO_ACCEPT).toContain(type)
    }
    // 合并后的 accept 同时包含两类，发布框/评论框共用
    expect(MEDIA_ACCEPT).toContain('image/heic')
    expect(MEDIA_ACCEPT).toContain('video/quicktime')
  })

  it('HEIC/AVIF/BMP 等新格式通过类型识别与大小校验', () => {
    for (const [name, type] of [
      ['photo.heic', 'image/heic'],
      ['photo.heif', 'image/heif'],
      ['photo.avif', 'image/avif'],
      ['photo.bmp', 'image/bmp'],
      ['clip.mov', 'video/quicktime'],
      ['clip.m4v', 'video/x-m4v'],
    ]) {
      const file = fakeFile(name, type)
      expect(detectMediaType(file), name).not.toBeNull()
      expect(validateMediaFile(file), name).toBeNull()
    }
  })

  it('非图片/视频仍被拒绝，超大文件仍被拦下', () => {
    expect(validateMediaFile(fakeFile('a.pdf', 'application/pdf'))).not.toBeNull()
    const huge = fakeFile('big.png', 'image/png', 11 * 1024 * 1024)
    expect(validateMediaFile(huge)).not.toBeNull()
  })

  it('上传超时按 0.5MB/s 估算，且不低于 120 秒', () => {
    // 小文件走保底 120s
    expect(uploadTimeoutMs(1024)).toBe(120_000)
    // 200MB ≈ 400s，不再被固定 120s 卡死
    expect(uploadTimeoutMs(200 * 1024 * 1024)).toBe(400_000)
  })

  it('HEIC 被标记为「无法预览」，用于提示按原图上传', () => {
    expect(isPreviewUnsupported(fakeFile('IMG_0001.HEIC', ''))).toBe(true)
    expect(isPreviewUnsupported(fakeFile('x.bin', 'image/heif'))).toBe(true)
    expect(isPreviewUnsupported(fakeFile('a.png', 'image/png'))).toBe(false)
  })
})
