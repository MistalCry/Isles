import http from 'node:http'

/**
 * 本地 Mock OpenAI 兼容服务（开发/测试用，不参与生产）。
 * 用途：LMStudio 未运行时，验证「频道问问 / 频道助手 / 平台 AI 配置」链路。
 *
 * 启动：node tools/mock-openai/server.mjs [port]   （默认 1234）
 * 接口：
 *   GET  /v1/models
 *   POST /v1/chat/completions （支持 stream=true 的 SSE 流式）
 *   GET  /health
 */

const PORT = Number(process.argv[2] || 1234)

const MODELS = [
  { id: 'mock-chat', object: 'model', owned_by: 'isles-mock' },
  { id: 'mock-vision', object: 'model', owned_by: 'isles-mock' },
  { id: 'mock-reasoner', object: 'model', owned_by: 'isles-mock' },
]

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = ''
    req.on('data', (chunk) => {
      data += chunk
    })
    req.on('end', () => {
      try {
        resolve(data ? JSON.parse(data) : {})
      } catch (error) {
        reject(error)
      }
    })
    req.on('error', reject)
  })
}

function lastUserContent(messages) {
  const list = Array.isArray(messages) ? messages : []
  for (let i = list.length - 1; i >= 0; i -= 1) {
    if (list[i]?.role === 'user') {
      const content = list[i].content
      if (typeof content === 'string') return content
      if (Array.isArray(content)) {
        const text = content.filter((p) => p?.type === 'text').map((p) => p.text).join('\n')
        const images = content.filter((p) => p?.type === 'image_url').length
        return `${text}${images ? `\n[收到 ${images} 张图片]` : ''}`
      }
    }
  }
  return ''
}

function answerFor(body) {
  const question = lastUserContent(body.messages)
  const model = body.model || 'mock-chat'
  return [
    `【Mock 回答｜${model}】`,
    question ? `你的问题是：${question}` : '没有收到问题内容。',
    '这是本地 mock AI 服务的流式回答，用于联调频道问问与频道助手功能。',
    '来源引用：mock 不会真的检索帖子；真实环境请配置 LMStudio（OpenAI 兼容）后使用。',
  ].join('\n')
}

function json(res, status, payload) {
  const data = JSON.stringify(payload)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(data),
  })
  res.end(data)
}

function sendStream(req, res, body) {
  const content = answerFor(body)
  const id = `chatcmpl-mock-${Date.now()}`
  const created = Math.floor(Date.now() / 1000)
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  })
  const chunks = content.match(/[\s\S]{1,12}/g) || []
  let index = 0
  const timer = setInterval(() => {
    if (index >= chunks.length) {
      clearInterval(timer)
      res.write(`data: ${JSON.stringify({ id, object: 'chat.completion.chunk', created, model: body.model, choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] })}\n\n`)
      res.write('data: [DONE]\n\n')
      res.end()
      return
    }
    const delta = { content: chunks[index] }
    res.write(`data: ${JSON.stringify({ id, object: 'chat.completion.chunk', created, model: body.model, choices: [{ index: 0, delta, finish_reason: null }] })}\n\n`)
    index += 1
  }, 20)
  req.on('close', () => clearInterval(timer))
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`)
  if (url.pathname === '/health') return json(res, 200, { status: 'UP', service: 'mock-openai' })
  if (url.pathname === '/v1/models' && req.method === 'GET') return json(res, 200, { object: 'list', data: MODELS })
  if (url.pathname === '/v1/chat/completions' && req.method === 'POST') {
    try {
      const body = await readBody(req)
      if (body.stream === true) return sendStream(req, res, body)
      const content = answerFor(body)
      return json(res, 200, {
        id: `chatcmpl-mock-${Date.now()}`,
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: body.model || 'mock-chat',
        choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
        usage: { prompt_tokens: 100, completion_tokens: 50, total_tokens: 150 },
      })
    } catch (error) {
      return json(res, 400, { error: { message: `bad request: ${error.message}`, type: 'invalid_request_error' } })
    }
  }
  json(res, 404, { error: { message: 'not found', type: 'invalid_request_error' } })
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[mock-openai] listening on http://127.0.0.1:${PORT}`)
})
