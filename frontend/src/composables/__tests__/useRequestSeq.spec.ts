import { describe, expect, it } from 'vitest'
import { useRequestSeq } from '@/composables/useRequestSeq'

// 这个小工具是十几处竞态修复的共同基础：语义一旦走样，页面会「随机显示旧数据」。
describe('useRequestSeq', () => {
  it('只有最后一次 next() 拿到的序号是最新的', () => {
    const seq = useRequestSeq()
    const first = seq.next()
    const second = seq.next()

    expect(seq.isLatest(first)).toBe(false)
    expect(seq.isLatest(second)).toBe(true)
  })

  it('invalidate 会让在途请求全部作废（如搜索框被清空）', () => {
    const seq = useRequestSeq()
    const ticket = seq.next()
    expect(seq.isLatest(ticket)).toBe(true)

    seq.invalidate()
    expect(seq.isLatest(ticket)).toBe(false)

    // 作废之后仍能正常领新号
    const nextTicket = seq.next()
    expect(seq.isLatest(nextTicket)).toBe(true)
  })

  it('多个实例互不影响（每个加载各自独立）', () => {
    const channelSeq = useRequestSeq()
    const pinnedSeq = useRequestSeq()

    const channelTicket = channelSeq.next()
    pinnedSeq.next()

    expect(channelSeq.isLatest(channelTicket)).toBe(true)
  })

  it('乱序响应：后发的先回、先发的后回 → 只接受最后一次（不显示旧数据）', async () => {
    const seq = useRequestSeq()
    let state = ''

    // 模拟页面的 load()：进函数先领号，await 回来再判 isLatest
    function load(value: string, delay: number): Promise<void> {
      const ticket = seq.next()
      return new Promise((resolve) => {
        setTimeout(() => {
          if (seq.isLatest(ticket)) state = value
          resolve()
        }, delay)
      })
    }

    const stale = load('A 旧频道', 30) // 先发，慢
    const fresh = load('B 新频道', 5) // 后发，快
    await Promise.all([stale, fresh])

    expect(state).toBe('B 新频道')
  })

  it('乱序响应：三个并发只有最后一个能写状态', async () => {
    const seq = useRequestSeq()
    const writes: string[] = []

    function load(value: string, delay: number): Promise<void> {
      const ticket = seq.next()
      return new Promise((resolve) => {
        setTimeout(() => {
          if (seq.isLatest(ticket)) writes.push(value)
          resolve()
        }, delay)
      })
    }

    await Promise.all([load('first', 30), load('second', 5), load('third', 15)])

    // 只有 third 领到的号是最新的；first/second 回来时都已过期
    expect(writes).toEqual(['third'])
  })

  it('顺序响应：请求一个接一个完成时，每次都能写（不会误杀）', async () => {
    const seq = useRequestSeq()
    const writes: string[] = []

    async function load(value: string): Promise<void> {
      const ticket = seq.next()
      await Promise.resolve()
      if (seq.isLatest(ticket)) writes.push(value)
    }

    await load('page1')
    await load('page2')

    expect(writes).toEqual(['page1', 'page2'])
  })

  it('invalidate 之后，在途请求即使成功回来也不能写状态', async () => {
    const seq = useRequestSeq()
    let state = 'initial'

    const ticket = seq.next()
    const pending = Promise.resolve().then(() => {
      if (seq.isLatest(ticket)) state = 'stale'
    })

    seq.invalidate() // 例如搜索框被清空
    await pending

    expect(state).toBe('initial')
  })

  it('旧序号不会被后续 next() 复活', () => {
    const seq = useRequestSeq()
    const first = seq.next()
    seq.next()
    seq.next()
    expect(seq.isLatest(first)).toBe(false)

    // 新号仍然有效，且与旧号不相等
    const latest = seq.next()
    expect(seq.isLatest(latest)).toBe(true)
    expect(latest).not.toBe(first)
  })
})
