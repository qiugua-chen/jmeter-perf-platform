#!/usr/bin/env python3
"""
error-details.py — 从 JTL 结果文件提取错误明细，生成 UTF-8 HTML 报告页

输出内容:
    1. 总览：样本总数 / 错误数 / 错误率
    2. 错误分组表：按 (label, responseCode, responseMessage) 分组，
       附首现样本 URL 与失败断言摘要（failureMessage 截断 200 字符）
    3. 明细最多展示 Top 100 组，超出在页首提示截断

用法:
    error-details.py <result.jtl> <输出.html> [标题，默认"错误样本"]

退出码: 恒为 0（门禁判定是 gate.py 的职责，本工具只产出报告）
"""
import sys
import csv
import html
import collections

TOP_N = 100
FAIL_MSG_CAP = 200
URL_CAP = 100


def esc(s: str) -> str:
    return html.escape(str(s))


def main():
    if len(sys.argv) < 3:
        print("用法: error-details.py <result.jtl> <输出.html> [标题]",
              file=sys.stderr)
        sys.exit(2)
    src, out = sys.argv[1], sys.argv[2]
    title = sys.argv[3] if len(sys.argv) > 3 else "错误样本"

    with open(src, encoding='utf-8') as f:
        rows = list(csv.DictReader(f))

    total = len(rows)
    errors = [r for r in rows if r.get('success') == 'false']
    rate = len(errors) / total * 100 if total else 0.0

    # 分组：label + responseCode + responseMessage；记录首现 URL 与失败摘要
    groups = collections.OrderedDict()
    for r in errors:
        key = (
            r.get('label', '?'),
            r.get('responseCode', '?'),
            (r.get('responseMessage', '') or ''),
        )
        if key not in groups:
            groups[key] = {
                'count': 0,
                'url': (r.get('URL', '') or '')[:URL_CAP],
                'failure': (r.get('failureMessage', '') or '')[:FAIL_MSG_CAP],
            }
        groups[key]['count'] += 1

    items = sorted(groups.items(), key=lambda kv: kv[1]['count'], reverse=True)

    parts = [
        f'<meta charset="UTF-8"><title>{esc(title)}</title>',
        '<style>body{font:13px sans-serif;margin:16px}'
        'table{border-collapse:collapse;width:100%}'
        'th,td{border:1px solid #ccc;padding:4px 8px;text-align:left;vertical-align:top}'
        'th{background:#f3f3f3}.code{color:#c00;font-family:monospace}</style>',
        f'<h2>{esc(title)}</h2>',
        f'<p>样本总数: <b>{total}</b>　错误数: <b class="code">{len(errors)}</b>'
        f'　错误率: <b class="code">{rate:.2f}%</b></p>',
    ]
    if not errors:
        parts.append('<p>✅ 无错误样本</p>')
    else:
        if len(items) > TOP_N:
            parts.append(f'<p>…(错误分组共 {len(items)} 组，仅展示 Top {TOP_N})…</p>')
        parts.append('<table><tr><th>#</th><th>请求</th><th>响应码</th>'
                     '<th>响应消息</th><th>次数</th><th>首现 URL</th>'
                     '<th>失败摘要</th></tr>')
        for i, (key, g) in enumerate(items[:TOP_N], 1):
            label, code, msg = key
            parts.append(
                f'<tr><td>{i}</td><td>{esc(label)}</td>'
                f'<td class="code">{esc(code)}</td><td>{esc(msg)}</td>'
                f'<td>{g["count"]}</td><td>{esc(g["url"])}</td>'
                f'<td>{esc(g["failure"])}</td></tr>')
        parts.append('</table>')

    open(out, 'w', encoding='utf-8').write(''.join(parts))


if __name__ == '__main__':
    main()
