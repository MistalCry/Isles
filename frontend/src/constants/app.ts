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

/** 举报原因：返回数组而非常量，保证切换语言后重新取到当前语言文案 */
export function reportReasons(): string[] {
  return [
    t('report.reason.spam'),
    t('report.reason.abuse'),
    t('report.reason.porn'),
    t('report.reason.illegal'),
    t('common.other'),
  ]
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
