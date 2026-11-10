#!/usr/bin/env python3
"""
textpage.py — 为文本类工件生成 UTF-8 HTML 查看页

背景：Jenkins 原生工件预览以 text/plain 无 charset 返回 → 中文乱码。
本工具生成带 <meta charset="UTF-8"> 的 HTML 包装页，作为自愈方案。

用法:
    textpage.py <源文件> <输出.html> <标题>

特性:
    - 大文件截尾（>512KB 仅保留末尾 512KB），避免浏览器卡死
    - HTML 转义，直接展示原始内容
"""
import sys
import html

def main():
    if len(sys.argv) != 4:
        print("用法: textpage.py <源文件> <输出.html> <标题>", file=sys.stderr)
        sys.exit(2)
    src, out, title = sys.argv[1:4]

    body = open(src, encoding='utf-8', errors='replace').read()
    if len(body.encode('utf-8')) > 512 * 1024:
        body = "…(文件过大，仅显示末尾 512KB)…\n" + body[-512 * 1024:]

    open(out, 'w', encoding='utf-8').write(
        f'<meta charset="UTF-8"><title>{html.escape(title)}</title>'
        f'<pre style="white-space:pre-wrap;font:12px monospace">{html.escape(body)}</pre>')

if __name__ == '__main__':
    main()
