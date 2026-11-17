/**
 * 请求序号：给「响应回来时可能已经过期」的异步场景一个统一判断依据。
 *
 * <p>典型用法（切频道 / 切会话 / 翻页 / 联想）：
 * <pre>
 * const seq = useRequestSeq()
 *
 * async function load(): Promise&lt;void&gt; {
 *   const ticket = seq.next()
 *   const data = await api.load()
 *   if (!seq.isLatest(ticket)) return // 期间又发起了新请求，丢弃这次结果
 *   state.value = data
 * }
 * </pre>
 *
 * <p>三条约定：
 * <ol>
 *   <li>每个 load 一进来就 `next()`，**在 await 之前**拿到序号；</li>
 *   <li>所有写状态的地方（含 catch / finally 里的 loading 收尾）都要先 `isLatest` 判断，
 *       否则旧请求仍会把 loading 提前关掉；</li>
 *   <li>需要主动作废在途请求（如搜索框被清空）时调 `invalidate()`。</li>
 * </ol>
 *
 * <p>与 {@link import('./useCursorList')} 内部的做法一致，但这里只提供序号，
 * 不绑定分页语义，页面级的 `load()` 可以直接用。
 */
export interface RequestSeq {
  /** 领取下一个序号（同时让此前所有在途请求作废） */
  next: () => number
  /** 这个序号是否仍是最新的（false = 已有更新的请求发出，应当丢弃本次结果） */
  isLatest: (seq: number) => boolean
  /** 作废所有在途请求，不领取新序号 */
  invalidate: () => void
}

export function useRequestSeq(): RequestSeq {
  let current = 0

  return {
    next: () => {
      current += 1
      return current
    },
    isLatest: (seq: number) => seq === current,
    invalidate: () => {
      current += 1
    },
  }
}
