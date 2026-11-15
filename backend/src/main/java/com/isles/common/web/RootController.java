package com.isles.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后端根路径落地页。
 *
 * <p>本项目是前后端分离：后端只提供 {@code /api/v1} 接口与 {@code /uploads} 静态资源，
 * 直接打开 {@code http://127.0.0.1:8080/} 以前会返回 401/Whitelabel，容易被误判成「后端挂了」。
 * 这里返回一个极简说明页，并给出前端地址与健康检查链接。
 */
@RestController
public class RootController {

    private final String frontendUrl;
    private final String contextPath;

    public RootController(@Value("${app.frontend-url:http://127.0.0.1:5173}") String frontendUrl,
                          @Value("${server.servlet.context-path:}") String contextPath) {
        this.frontendUrl = frontendUrl;
        this.contextPath = contextPath;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String index() {
        String base = contextPath == null ? "" : contextPath;
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                  <title>群岛 Isles · 后端已启动</title>
                  <style>
                    body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
                           font: 15px/1.7 -apple-system, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
                           background:#f5f7fb; color:#1f2937; }
                    .card { width:min(560px, 92vw); padding:32px 36px; background:#fff; border-radius:16px;
                            box-shadow:0 8px 30px rgba(15,23,42,.08); }
                    h1 { margin:0 0 6px; font-size:20px; }
                    .sub { margin:0 0 20px; color:#64748b; font-size:13px; }
                    .row { display:flex; gap:10px; align-items:baseline; padding:9px 0; border-top:1px solid #eef2f7; }
                    .k { width:96px; color:#94a3b8; font-size:13px; flex:none; }
                    a { color:#2563eb; text-decoration:none; word-break:break-all; }
                    a:hover { text-decoration:underline; }
                    code { padding:2px 6px; background:#f1f5f9; border-radius:6px; font-size:12.5px; }
                    .tip { margin-top:18px; padding:12px 14px; background:#f8fafc; border-radius:10px;
                           color:#475569; font-size:13px; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>群岛 Isles · 后端已启动</h1>
                    <p class="sub">这是 API 服务，没有前端页面。请用下面的地址打开前端。</p>
                    <div class="row"><span class="k">前端页面</span><a href="%s">%s</a></div>
                    <div class="row"><span class="k">健康检查</span><a href="%s/actuator/health">%s/actuator/health</a></div>
                    <div class="row"><span class="k">接口前缀</span><code>%s/api/v1</code></div>
                    <div class="row"><span class="k">上传资源</span><code>%s/uploads/...</code></div>
                    <div class="tip">
                      前端未启动？在 <code>frontend</code> 目录执行 <code>pnpm dev</code>；
                      Windows 也可在项目根目录运行 <code>isles.bat start</code> 一键启动前后端。
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(frontendUrl, frontendUrl, base, base, base, base);
    }
}
