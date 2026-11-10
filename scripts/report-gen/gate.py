#!/usr/bin/env python3
"""
gate.py — 门禁：错误率超阈值 → 构建 FAILED（但报告完整产出）

定位: FAILED 表示"未过质量线"，不等于"运行失败"——门禁即状态。
阈值用参数显式传入并回显，避免"魔法数字"散落在各任务配置里。

用法:
    gate.py <result.jtl> [阈值百分比，默认 0.5]

退出码:
    0 — 门禁通过（或压测本身失败的兜底也已由 build-command.sh 先行拦截）
    1 — 错误率 > 阈值
    2 — 参数错误
"""
import sys
import csv


def main():
    if len(sys.argv) < 2:
        print("用法: gate.py <result.jtl> [阈值百分比，默认 0.5]", file=sys.stderr)
        sys.exit(2)
    src = sys.argv[1]
    threshold = float(sys.argv[2]) if len(sys.argv) > 2 else 0.5

    with open(src, encoding='utf-8') as f:
        rows = list(csv.DictReader(f))

    total = len(rows)
    errors = sum(1 for r in rows if r.get('success') == 'false')
    rate = errors / total * 100 if total else 0.0

    print(f"门禁: 样本 {total}, 错误 {errors} ({rate:.2f}%)")
    if rate > threshold:
        print(f"❌ 门禁未通过: 错误率 {rate:.2f}% > {threshold:g}%")
        sys.exit(1)
    print("✅ 门禁通过")


if __name__ == '__main__':
    main()
