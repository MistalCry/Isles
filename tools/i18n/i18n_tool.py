"""i18n 文案工具（入库，随代码维护）。

用法：
  1. 在 CATALOG 里补 `'模块.key': ('中文', 'English')`
  2. `python tools/i18n/i18n_tool.py gen`   → 重新生成 src/i18n/zh-CN.ts / en-US.ts（会转义 @ 与 |）
  3. `python tools/i18n/i18n_tool.py apply` → 按中文原文批量替换 .vue/.ts 里的字面量（迁移期用）
  4. `python tools/i18n/i18n_tool.py check` → 列出仍未迁移的中文（不含注释/console）

替换规则：
  - 模板文本节点 `>中文<`            → `>{{ t('key') }}<`
  - 静态属性 `placeholder="中文"`    → `:placeholder="t('key')"`
  - 脚本字符串 `'中文'` / "中文"      → `t('key')`（自动补 import）
  - 模板表达式里的 `'中文'`（如 :label="'中文'"）同样替换
"""

import io
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SRC = os.path.join(ROOT, 'frontend', 'src')

# ---------------------------------------------------------------------------
# 目录：dotted key -> (中文, English)
# ---------------------------------------------------------------------------
CATALOG = json.load(io.open(os.path.join(os.path.dirname(__file__), 'catalog.json'), encoding='utf-8'))

SKIP_DIRS = {'__tests__', 'i18n', 'node_modules'}
ATTRS = ('placeholder', 'title', 'label', 'aria-label', 'alt', 'content', 'description',
         'confirm-button-text', 'cancel-button-text', 'header', 'empty-text', 'hint')


def iter_files():
    for root, dirs, files in os.walk(SRC):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            if f.endswith(('.vue', '.ts')):
                yield os.path.join(root, f)


def nested(pairs):
    """把 dotted key 展开成嵌套 dict"""
    out = {}
    for key, value in pairs.items():
        parts = key.split('.')
        node = out
        for part in parts[:-1]:
            node = node.setdefault(part, {})
        node[parts[-1]] = value
    return out


def escape_message(text):
    """vue-i18n 里 @ 是链接语法、| 是复数分隔符，出现在文案里必须转义，
    否则运行时报 `Message compilation error: Invalid linked format`。"""
    return text.replace('@', "{'@'}").replace('|', "{'|'}")


def dump_ts(obj, indent=0):
    pad = '  ' * indent
    lines = []
    for k, v in obj.items():
        if isinstance(v, dict):
            lines.append(f'{pad}{k}: {{')
            lines.append(dump_ts(v, indent + 1))
            lines.append(f'{pad}}},')
        else:
            lines.append(f"{pad}{k}: {json.dumps(escape_message(v), ensure_ascii=False)},")
    return '\n'.join(lines)


def gen():
    zh = nested({k: v[0] for k, v in CATALOG.items()})
    en = nested({k: v[1] for k, v in CATALOG.items()})
    header = ('/**\n * 由 .run/i18n_tool.py 从 i18n_catalog.json 生成，请勿手改。\n'
              ' * 新增文案：改 catalog → `python .run/i18n_tool.py gen`。\n */\n')
    io.open(os.path.join(SRC, 'i18n', 'zh-CN.ts'), 'w', encoding='utf-8').write(
        header + 'export default {\n' + dump_ts(zh) + '\n} as const\n')
    io.open(os.path.join(SRC, 'i18n', 'en-US.ts'), 'w', encoding='utf-8').write(
        header + 'export default {\n' + dump_ts(en) + '\n} as const\n')
    print(f'generated {len(CATALOG)} keys')


def key_for(text):
    """中文原文 -> key（取最长匹配，避免短词误伤）"""
    candidates = [k for k, v in CATALOG.items() if v[0] == text]
    return candidates[0] if candidates else None


def ensure_import(src, is_vue):
    """确保 t 可用：.vue 注入 useI18n，.ts 注入全局 t。"""
    if is_vue:
        if '<script setup' not in src:
            return src
        idx = src.index('<script setup')
        end = src.index('>', idx) + 1
        head, body = src[:end], src[end:]
        if 'useI18n' not in body:
            body = "\nimport { useI18n } from 'vue-i18n'" + body
        if "const { t }" not in body:
            lines = body.split('\n')
            last_import = 0
            for i, line in enumerate(lines):
                if line.startswith('import '):
                    last_import = i
            lines.insert(last_import + 1, "\nconst { t } = useI18n()")
            body = '\n'.join(lines)
        return head + body
    if "from '@/i18n'" not in src and "from './i18n'" not in src:
        return "import { t } from '@/i18n'\n" + src
    return src


def apply():
    replaced_files = 0
    total = 0
    # 长文本优先，避免「取消」先匹配掉「取消回复」
    ordered = sorted(CATALOG.items(), key=lambda kv: -len(kv[1][0]))
    for path in iter_files():
        src = io.open(path, encoding='utf-8').read()
        original = src
        is_vue = path.endswith('.vue')

        for key, (zh, _en) in ordered:
            if not zh:
                continue
            esc = re.escape(zh)
            # 1) 模板文本节点
            src = re.sub(r'>(\s*)' + esc + r'(\s*)<', lambda m, k=key: ">{{ t('" + k + "') }}<", src)
            # 2) 静态属性
            for attr in sorted(ATTRS, key=len, reverse=True):
                src = re.sub(r'(?<![-\w])' + re.escape(attr) + r'="' + esc + r'"',
                             lambda m, k=key, a=attr: f':{a}="t(\'{k}\')"', src)
            # 3) 单引号 / 双引号字符串（脚本与模板表达式）
            src = src.replace(f"'{zh}'", f"t('{key}')")
            src = src.replace(f'"{zh}"', f"t('{key}')")
            # 4) 模板插值里的裸中文（{{ 中文 }}）
            src = re.sub(r'\{\{\s*' + esc + r'\s*\}\}', lambda m, k=key: "{{ t('" + k + "') }}", src)

        if src != original:
            if 't(' in src:
                src = ensure_import(src, is_vue)
            replaced_files += 1
            total += 1
            io.open(path, 'w', encoding='utf-8').write(src)
    print(f'apply: {replaced_files} files touched')


def check():
    leftovers = []
    for path in iter_files():
        src = io.open(path, encoding='utf-8').read()
        for i, line in enumerate(src.splitlines(), 1):
            s = line.strip()
            if s.startswith(('//', '*', '/*', '<!--')):
                continue
            if 'console.' in s:
                continue
            if re.search(r'[\u4e00-\u9fa5]', line):
                leftovers.append(f'{os.path.relpath(path, SRC)}:{i}: {s[:90]}')
    print(f'leftover lines: {len(leftovers)}')
    for item in leftovers[:80]:
        print(' ', item)


if __name__ == '__main__':
    {'gen': gen, 'apply': apply, 'check': check}[sys.argv[1]]()
