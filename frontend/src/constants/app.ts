import type { Gender } from '@/types/models'

export const APP_NAME = '群岛'
export const APP_NAME_EN = 'Isles'
export const APP_SLOGAN = '每个频道是一座岛，在岛与岛之间发现同频的人。'

export const PAGE_SIZE = 20
export const SEARCH_PAGE_SIZE = 20
export const MAX_SEARCH_HISTORY = 10
export const MAX_MEDIA_COUNT = 9

export const MAX_IMAGE_SIZE_MB = 10
export const MAX_VIDEO_SIZE_MB = 200

export const GENDER_LABELS: Record<Gender, string> = {
  MALE: '男',
  FEMALE: '女',
  OTHER: '其他',
  UNKNOWN: '保密',
}

export const CHANNEL_TYPE_LABELS = {
  PUBLIC: '公开频道',
  PRIVATE: '私密频道',
} as const

export const CHANNEL_VISIBILITY_LABELS = {
  PUBLIC_VISIBLE: '公开可见',
  MEMBERS_ONLY: '仅成员可见',
} as const

/** 举报原因（M1 仅占位，M2 接入审核队列）。 */
export const REPORT_REASONS = ['垃圾广告', '人身攻击', '色情低俗', '违法违规', '其他'] as const

export const POST_TAB_LABELS = {
  latest: '全部',
  hot: '热门',
  essence: '精华',
} as const

export const EMOJI_LIST = ['😀', '😂', '🥰', '🤔', '👍', '🎉', '🌊', '🏝️', '❤️', '🔥'] as const
