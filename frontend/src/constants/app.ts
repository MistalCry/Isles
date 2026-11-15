import { t } from '@/i18n'
import type { Gender } from '@/types/models'

/** 品牌名不参与翻译，仅作为站点配置未加载时的兜底 */
/** 品牌名不参与翻译，仅作为站点配置未加载时的兜底 */
export const APP_NAME = '群岛'
export const APP_NAME_EN = 'Isles'
/** 文案类常量一律用 getter：模块加载时求值会导致切语言不生效 */
export function appSlogan(): string {
  return t('app.slogan')
}

export const PAGE_SIZE = 20
export const SEARCH_PAGE_SIZE = 20
export const MAX_SEARCH_HISTORY = 10
export const MAX_MEDIA_COUNT = 9

/**
 * 上传控件 accept：与产品支持的格式保持一致（后端 MIME 白名单另有一套，
 * 这里放宽是为了让系统文件框能选到，真正校验仍以后端为准）。
 */
export const IMAGE_ACCEPT = [
  'image/jpeg',
  'image/png',
  'image/gif',
  'image/webp',
  'image/avif',
  'image/bmp',
  'image/heic',
  'image/heif',
].join(',')

export const VIDEO_ACCEPT = ['video/mp4', 'video/webm', 'video/quicktime', 'video/x-m4v'].join(',')

export const MEDIA_ACCEPT = `${IMAGE_ACCEPT},${VIDEO_ACCEPT}`

export const MAX_IMAGE_SIZE_MB = 10
export const MAX_VIDEO_SIZE_MB = 200

export const GENDER_LABELS: Record<Gender, string> = {
  get MALE() {
    return t('profile.male')
  },
  get FEMALE() {
    return t('profile.female')
  },
  get OTHER() {
    return t('common.other')
  },
  get UNKNOWN() {
    return t('gender.private')
  },
}

export const CHANNEL_TYPE_LABELS = {
  get PUBLIC() {
    return t('channel.typePublic')
  },
  get PRIVATE() {
    return t('channel.typePrivate')
  },
} as const

export const CHANNEL_VISIBILITY_LABELS = {
  get PUBLIC_VISIBLE() {
    return t('channel.publicVisible')
  },
  get MEMBERS_ONLY() {
    return t('channel.visibilityMembers')
  },
} as const

/**
 * 举报原因代码：必须与后端 `ReportCreateRequest.reason` 的
 * `@Pattern(SPAM|ABUSE|PORN|ILLEGAL|OTHER)` 完全一致。
 * 提交接口只认代码，展示才用文案（见 reportReasonLabel）。
 */
export const REPORT_REASON_CODES = ['SPAM', 'ABUSE', 'PORN', 'ILLEGAL', 'OTHER'] as const
export type ReportReasonCode = (typeof REPORT_REASON_CODES)[number]

const REPORT_REASON_LABEL_KEYS: Record<ReportReasonCode, string> = {
  SPAM: 'report.reason.spam',
  ABUSE: 'report.reason.abuse',
  PORN: 'report.reason.porn',
  ILLEGAL: 'report.reason.illegal',
  OTHER: 'common.other',
}

/** 举报原因代码 → 当前语言文案；未知代码原样返回，避免列表出现空白 */
export function reportReasonLabel(code?: string | null): string {
  if (!code) return ''
  const key = REPORT_REASON_LABEL_KEYS[code as ReportReasonCode]
  return key ? t(key) : code
}

export const POST_TAB_LABELS = {
  get latest() {
    return t('common.all')
  },
  get hot() {
    return t('channel.page.hot')
  },
  get essence() {
    return t('post.essence')
  },
} as const

export const EMOJI_LIST = ['😀', '😂', '🥰', '🤔', '👍', '🎉', '🌊', '🏝️', '❤️', '🔥'] as const
