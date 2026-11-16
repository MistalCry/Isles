import http from 'node:http'

/**
 * 本地 Mock OpenAI 兼容服务（开发/测试用，不参与生产）。
 * 用途：LMStudio 未运行时，验证「频道问问 / 频道助手 / 平台 AI 配置 / 工具调用」链路。
 *
 * 启动：node tools/mock-openai/server.mjs [port]   （默认 1234）
 * 接口：
 *   GET  /v1/models
 *   POST /v1/chat/completions （支持 stream=true 的 SSE 流式、支持 tools/tool_calls）
 *   GET  /health
 *
 * 工具调用两段式（供测试同事验证 function calling 全链路）：
 *   1) 请求带 tools 且历史里没有 role="tool" 的消息 → 返回 tool_calls（先要工具）；
 *      stream=true 时按 index 分片下发 id / name / arguments，模拟真实增量。
 *   2) 请求里已包含 role="tool" 的结果 → 返回自然语言回答，并在正文里带上工具结果摘要。
 * 若想强制关闭工具行为，可在请求体加 "mockTools": false。
 * 调试接口（测试用）：GET /debug/requests 返回最近 200 次请求的摘要（model / tools / 是否带 tool 结果），
 * DELETE /debug/requests 清空。用于断言「模型要工具 → 检索 → 二轮回答」以及「服务端实际用了哪个模型」。
 * 模型名用 "mock-no-tools" 时，只要请求带 tools 就返回 400（模拟「模型不支持 function calling」），
 * 用于验证服务端的自动降级（关闭工具 + 关键词检索重试）。
 */

const PORT = Number(process.argv[2] || 1234)

/** 最近请求摘要（供 e2e 断言轮次与模型，仅测试用，不落盘） */
const requestLog = []
const REQUEST_LOG_LIMIT = 200

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

function textOfContent(content) {
  if (typeof content === 'string') return content
  if (Array.isArray(content)) {
    return content
      .filter((p) => p?.type === 'text')
      .map((p) => p.text)
      .join('\n')
  }
  return ''
}

function lastUserContent(messages) {
  const list = Array.isArray(messages) ? messages : []
  for (let i = list.length - 1; i >= 0; i -= 1) {
    if (list[i]?.role === 'user') {
      const text = textOfContent(list[i].content)
      const images = Array.isArray(list[i].content)
        ? list[i].content.filter((p) => p?.type === 'image_url').length
        : 0
      return `${text}${images ? `\n[收到 ${images} 张图片]` : ''}`
    }
  }
  return ''
}

function toolMessages(messages) {
  const list = Array.isArray(messages) ? messages : []
  return list.filter((m) => m?.role === 'tool')
}

function availableToolNames(body) {
  const tools = Array.isArray(body.tools) ? body.tools : []
  return tools
    .map((tool) => tool?.function?.name)
    .filter((name) => typeof name === 'string' && name.length > 0)
}

/** 第一段：决定要调用哪个工具，并拼出 arguments。 */
function planToolCall(body) {
  const names = availableToolNames(body)
  if (names.length === 0) return null
  if (body.mockTools === false) return null
  if (toolMessages(body.messages).length > 0) return null

  const question = lastUserContent(body.messages).trim()
  const query = question.length > 0 ? question.slice(0, 40) : '社区'
  const searchTool = names.includes('search_channel_posts')
    ? 'search_channel_posts'
    : names.includes('search_global_posts')
      ? 'search_global_posts'
      : null
  if (!searchTool) return null
  return {
    id: `call_mock_${Date.now()}`,
    name: searchTool,
    arguments: JSON.stringify({ query, limit: 3 }),
  }
}

function answerFor(body) {
  const question = lastUserContent(body.messages)
  const model = body.model || 'mock-chat'
  const tools = toolMessages(body.messages)
  const lines = [`【Mock 回答｜${model}】`, question ? `你的问题是：${question}` : '没有收到问题内容。']
  if (tools.length > 0) {
    lines.push(`我调用了 ${tools.length} 次工具，工具返回摘要：`)
    for (const message of tools) {
      const text = textOfContent(message.content)
      lines.push(`- ${text.length > 200 ? `${text.slice(0, 200)}…` : text}`)
    }
    lines.push('以上引用来自工具实际检索结果 [1]。')
  } else {
    lines.push('这是本地 mock AI 服务的流式回答，用于联调频道问问与频道助手功能。')
    lines.push('来源引用：本次未调用工具（未开启工具调用或请求未带 tools）。')
  }
  return lines.join('\n')
}

function json(res, status, payload) {
  const data = JSON.stringify(payload)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(data),
  })
  res.end(data)
}

function writeChunk(res, body, delta, finishReason = null) {
  const chunk = {
    id: `chatcmpl-mock-${Date.now()}`,
    object: 'chat.completion.chunk',
    created: Math.floor(Date.now() / 1000),
    model: body.model || 'mock-chat',
    choices: [{ index: 0, delta, finish_reason: finishReason }],
  }
  res.write(`data: ${JSON.stringify(chunk)}\n\n`)
}

function sendToolCallStream(req, res, body, plan) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
  })
  const args = plan.arguments
  const splitAt = Math.max(1, Math.floor(args.length / 2))
  // 分片 1：index + id + name + arguments 前半段
  writeChunk(res, body, {
    tool_calls: [
      {
        index: 0,
        id: plan.id,
        type: 'function',
        function: { name: plan.name, arguments: args.slice(0, splitAt) },
      },
    ],
  })
  // 分片 2：只有 arguments 后半段（真实上游常见形态）
  writeChunk(res, body, {
    tool_calls: [{ index: 0, function: { arguments: args.slice(splitAt) } }],
  })
  writeChunk(res, body, {}, 'tool_calls')
  res.write('data: [DONE]\n\n')
  res.end()
  req.on('close', () => {})
}

function sendStream(req, res, body) {
  const content = answerFor(body)
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
      writeChunk(res, body, {}, 'stop')
      res.write('data: [DONE]\n\n')
      res.end()
      return
    }
    writeChunk(res, body, { content: chunks[index] })
    index += 1
  }, 20)
  req.on('close', () => clearInterval(timer))
}

function toolCallResponse(body, plan) {
  return {
    id: `chatcmpl-mock-${Date.now()}`,
    object: 'chat.completion',
    created: Math.floor(Date.now() / 1000),
    model: body.model || 'mock-chat',
    choices: [
      {
        index: 0,
        message: {
          role: 'assistant',
          content: null,
          tool_calls: [
            {
              id: plan.id,
              type: 'function',
              function: { name: plan.name, arguments: plan.arguments },
            },
          ],
        },
        finish_reason: 'tool_calls',
      },
    ],
    usage: { prompt_tokens: 120, completion_tokens: 20, total_tokens: 140 },
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`)
  if (url.pathname === '/health') return json(res, 200, { status: 'UP', service: 'mock-openai' })
  if (url.pathname === '/debug/requests' && req.method === 'GET') {
    return json(res, 200, { items: requestLog.slice() })
  }
  if (url.pathname === '/debug/requests' && req.method === 'DELETE') {
    requestLog.length = 0
    return json(res, 200, { cleared: true })
  }
  if (url.pathname === '/v1/models' && req.method === 'GET') return json(res, 200, { object: 'list', data: MODELS })
  if (url.pathname === '/v1/chat/completions' && req.method === 'POST') {
    try {
      const body = await readBody(req)
      requestLog.push({
        ts: Date.now(),
        model: body.model,
        stream: body.stream === true,
        tools: availableToolNames(body),
        toolResultCount: toolMessages(body.messages).length,
        userText: lastUserContent(body.messages).slice(0, 80),
        // 系统提示词摘要：用来断言「模型不支持图片时服务端是否真的加了忽略提示」
        systemText: (Array.isArray(body.messages) ? body.messages : [])
          .filter((m) => m?.role === 'system')
          .map((m) => textOfContent(m.content))
          .join(' | ')
          .slice(0, 300),
        // 服务端在「模型不支持视觉」时会追加一句系统提示，这里显式标记便于断言（不受截断影响）
        ignoredImagesNote: /已忽略\s*\d+\s*张图片/.test(
          (Array.isArray(body.messages) ? body.messages : [])
            .filter((m) => m?.role === 'system')
            .map((m) => textOfContent(m.content))
            .join('\n'),
        ),
      })
      if (requestLog.length > REQUEST_LOG_LIMIT) requestLog.shift()
      if (body.model === 'mock-no-tools' && Array.isArray(body.tools) && body.tools.length > 0) {
        return json(res, 400, {
          error: { message: 'this model does not support tools', type: 'invalid_request_error' },
        })
      }
      const plan = planToolCall(body)
      if (body.stream === true) {
        if (plan) return sendToolCallStream(req, res, body, plan)
        return sendStream(req, res, body)
      }
      if (plan) return json(res, 200, toolCallResponse(body, plan))
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
