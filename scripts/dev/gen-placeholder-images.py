#!/usr/bin/env python3
"""为 /upload/** 生成占位图，修复本地启动后商品图全部 404 的问题。

背景
----
zeroai-mall 的商品图存放在作者私有 OSS，开源仓库不含素材（git 历史里从未提交过
upload 目录，公开演示站也已下线）。数据库里保存的是 /upload/<md5>.webp 这样的相对
路径，本地没有文件时 Spring 会抛 NoResourceFoundException。

本脚本从 MySQL 读出所有被引用的文件名，按用途生成尺寸、配色合适的 webp 占位图，
并把商品名/分类名画在图上，使前端页面可以直接浏览。

用法
----
    # 生成到默认目录（application.yml 里 uploadDir 的默认值）
    python scripts/dev/gen-placeholder-images.py

    # 指定目录 / 覆盖已有文件 / 只看统计不写盘
    python scripts/dev/gen-placeholder-images.py --out D:/zeroai-mall/webp --force
    python scripts/dev/gen-placeholder-images.py --dry-run

依赖：Pillow（需带 webp 支持）、numpy、可用的 docker（或本机 mysql 客户端）。
"""
from __future__ import annotations

import argparse
import colorsys
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------- 环境参数
DB_NAME = os.environ.get("ZEROAI_DB_NAME", "zeroai_mall")
DB_USER = os.environ.get("ZEROAI_DB_USER", "root")
DB_PASS = os.environ.get("ZEROAI_DB_PASS", "@@123456Java")
DOCKER_CONTAINER = os.environ.get("ZEROAI_DB_CONTAINER", "mysql")

FONT_REGULAR = r"C:/Windows/Fonts/msyh.ttc"
FONT_BOLD = r"C:/Windows/Fonts/msyhbd.ttc"

# 每种用途的尺寸；同日寸决定前端卡片是否变形
SIZES: dict[str, tuple[int, int]] = {
    "banner": (750, 360),
    "goods": (800, 800),
    "gallery": (800, 800),
    "detail": (750, 600),
    "spec": (400, 400),
    "order_goods": (800, 800),
    "category_icon": (160, 160),
    "category_pic": (400, 400),
    "diamond_icon": (160, 160),
    "diamond_pic": (400, 400),
    "diamond_value": (400, 400),
    "comment_pic": (500, 500),
    "avatar": (200, 200),
    "comment_avatar": (200, 200),
}
# 圆形图标类（金刚位、头像）：画成圆底 + 大字，比渐变方图更像真素材
ROUND_KINDS = {"category_icon", "diamond_icon", "diamond_value", "avatar", "comment_avatar"}
# 顺序即优先级：同一文件被多处引用时保留更"具体"的用途
PRIORITY = [
    "banner", "goods", "gallery", "detail", "spec", "order_goods",
    "category_pic", "category_icon", "diamond_pic", "diamond_icon", "diamond_value",
    "comment_pic", "avatar", "comment_avatar",
]

IMG_RE = re.compile(r"/upload/([0-9a-zA-Z._-]+\.webp)")
# 折行单元：英文/数字/型号串 → 空白 → 单个其它字符（中文）
TOKEN_RE = re.compile(r"[0-9A-Za-z][0-9A-Za-z.+&%\-]*|\s+|.")


# ---------------------------------------------------------------- 取数
def run_sql(sql: str) -> str:
    """优先走 docker 容器里的 mysql 客户端，失败时回退本机 mysql。"""
    base_cmd = ["mysql", f"-u{DB_USER}", f"-p{DB_PASS}", DB_NAME, "-N", "-B", "-e", sql]
    attempts = [["docker", "exec", DOCKER_CONTAINER] + base_cmd, base_cmd]
    last = None
    for cmd in attempts:
        try:
            proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", timeout=120)
        except FileNotFoundError as exc:
            last = exc
            continue
        if proc.returncode == 0:
            return proc.stdout
        last = RuntimeError(proc.stderr.strip()[:400])
    raise SystemExit(f"[x] 无法连接 MySQL：{last}")


def dec(text: str) -> str:
    """解码 SQL 里 hex() 出来的中文字段。"""
    try:
        return bytes.fromhex((text or "").strip()).decode("utf-8")
    except Exception:
        return ""


# 注意：不要用 to_base64()——MySQL 会每 76 字符插一个换行，把 TSV 行结构打散，
# 长字段（相册 JSON、详情 HTML）会被截断成空串。hex() 不插换行，安全。
# 结构化字段：kind<TAB>filename<TAB>hex(显示文字)
STRUCT_SQL = f"""
select 'goods', substring_index(substring_index(pic_url,'/upload/',-1),'?',1), hex(ifnull(name,''))
  from shop_goods where pic_url like '%upload%'
union all
select 'category_icon', substring_index(substring_index(icon_url,'/upload/',-1),'?',1), hex(ifnull(name,''))
  from shop_category where icon_url like '%upload%'
union all
select 'category_pic', substring_index(substring_index(pic_url,'/upload/',-1),'?',1), hex(ifnull(name,''))
  from shop_category where pic_url like '%upload%'
union all
select 'banner', substring_index(substring_index(img_url,'/upload/',-1),'?',1), hex(ifnull(title,''))
  from shop_banner where img_url like '%upload%'
union all
select 'diamond_icon', substring_index(substring_index(icon_url,'/upload/',-1),'?',1), hex(ifnull(name,''))
  from shop_diamond where icon_url like '%upload%'
union all
select 'diamond_pic', substring_index(substring_index(pic_url,'/upload/',-1),'?',1), hex(ifnull(name,''))
  from shop_diamond where pic_url like '%upload%'
union all
select 'diamond_value', substring_index(substring_index(value_url,'/upload/',-1),'?',1), hex(ifnull(name,''))
  from shop_diamond where value_url like '%upload%'
union all
select 'spec', substring_index(substring_index(pic_url,'/upload/',-1),'?',1), hex(concat(ifnull(specification,''),' ',ifnull(value,'')))
  from shop_goods_specification where pic_url like '%upload%'
union all
select 'avatar', substring_index(substring_index(avatar,'/upload/',-1),'?',1), hex(ifnull(nickname,''))
  from shop_member where avatar like '%upload%'
union all
select 'comment_avatar', substring_index(substring_index(avatar,'/upload/',-1),'?',1), hex(left(ifnull(content,''),2))
  from shop_comment where avatar like '%upload%'
union all
select 'order_goods', substring_index(substring_index(pic_url,'/upload/',-1),'?',1), hex(ifnull(goods_name,''))
  from shop_order_goods where pic_url like '%upload%'
"""

# 富文本：相册 JSON + 详情 HTML，图片名只能正则提取，文字继承所属商品名
RICHTEXT_SQL = """
select hex(ifnull(name,'')), hex(ifnull(gallery,'')), hex(ifnull(detail,''))
  from shop_goods
"""

COMMENT_PIC_SQL = """
select hex(ifnull(pic_urls,'')), hex(left(ifnull(content,''),8))
  from shop_comment where pic_urls like '%upload%'
"""


def collect() -> dict[str, tuple[str, str]]:
    """返回 {文件名: (用途, 显示文字)}。"""
    found: dict[str, tuple[str, str]] = {}

    def put(name: str, kind: str, text: str) -> None:
        name = name.strip()
        if not name or not name.lower().endswith(".webp"):
            return
        prev = found.get(name)
        if prev is None or PRIORITY.index(kind) < PRIORITY.index(prev[0]):
            found[name] = (kind, (text or "").strip())

    for line in run_sql(STRUCT_SQL).splitlines():
        parts = line.split("\t")
        if len(parts) >= 3:
            put(parts[1], parts[0], dec(parts[2]))

    # gallery / detail
    for line in run_sql(RICHTEXT_SQL).splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        goods_name = dec(parts[0])
        for kind, blob in (("gallery", dec(parts[1])), ("detail", dec(parts[2]))):
            for name in IMG_RE.findall(blob):
                put(name, kind, goods_name)

    # 评论晒图
    for line in run_sql(COMMENT_PIC_SQL).splitlines():
        parts = line.split("\t")
        if len(parts) >= 2:
            for name in IMG_RE.findall(dec(parts[0])):
                put(name, "comment_pic", dec(parts[1]))

    return found


# ---------------------------------------------------------------- 绘图
def palette(name: str) -> tuple[tuple[int, int, int], tuple[int, int, int], tuple[int, int, int]]:
    """按文件名 hash 取一组同色相的淡雅配色：(顶部, 底部, 文字/装饰)。"""
    h = int(hashlib.md5(name.encode()).hexdigest()[:6], 16) % 360 / 360.0
    top = colorsys.hsv_to_rgb(h, 0.05, 0.995)
    bottom = colorsys.hsv_to_rgb(h, 0.16, 0.945)
    ink = colorsys.hsv_to_rgb(h, 0.62, 0.30)
    to255 = lambda c: tuple(int(v * 255) for v in c)  # noqa: E731
    return to255(top), to255(bottom), to255(ink)


def gradient(size: tuple[int, int], top: tuple[int, int, int], bottom: tuple[int, int, int]) -> Image.Image:
    w, h = size
    tx = np.linspace(0.0, 1.0, w)[None, :]
    ty = np.linspace(0.0, 1.0, h)[:, None]
    t = np.clip(0.35 * tx + 0.65 * ty, 0.0, 1.0)[:, :, None]
    arr = np.array(top)[None, None, :] * (1 - t) + np.array(bottom)[None, None, :] * t
    return Image.fromarray(arr.astype(np.uint8), mode="RGB")


def wrap(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.FreeTypeFont, max_w: int) -> list[str]:
    """按"不可分单元"折行：英文单词、数字串、型号（如 60 Pro）整体不拆，中文按字断。"""
    lines, cur = [], ""
    for tok in TOKEN_RE.findall(text):
        if tok == "\n":
            lines.append(cur.rstrip())
            cur = ""
            continue
        cand = cur + tok
        if draw.textlength(cand, font=font) <= max_w or not cur.strip():
            cur = cand
        elif tok.isspace():
            lines.append(cur.rstrip())
            cur = ""
        else:
            lines.append(cur.rstrip())
            cur = tok.lstrip()
    if cur.strip():
        lines.append(cur.rstrip())
    return lines


def fit_font(draw, text, path, box_w, box_h, max_lines, hi=140, lo=12):
    """从大到小试字号，取能塞进 (box_w, max_lines) 且总高不超过 box_h 的最大值。"""
    for size in range(hi, lo - 1, -2):
        font = ImageFont.truetype(path, size)
        lines = wrap(draw, text, font, box_w)
        if len(lines) <= max_lines and len(lines) * size * 1.35 <= box_h:
            return font, lines
    font = ImageFont.truetype(path, lo)
    return font, wrap(draw, text, font, box_w)[:max_lines]


def draw_round_icon(img: Image.Image, name: str, text: str, ink) -> None:
    """金刚位图标 / 头像：圆底 + 大字。"""
    w, h = img.size
    draw = ImageDraw.Draw(img)
    r = int(min(w, h) * 0.46)
    base = tuple(min(255, int(c * 0.55 + 255 * 0.45)) for c in ink)
    draw.ellipse([w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r], fill=base)
    label = (text or "商品").strip()[:2]
    font = ImageFont.truetype(FONT_BOLD, int(r * 0.85))
    while draw.textlength(label, font=font) > r * 1.5 and font.size > 8:
        font = ImageFont.truetype(FONT_BOLD, font.size - 2)
    draw.text((w / 2, h / 2), label, font=font, fill=(255, 255, 255), anchor="mm")


def draw_card(img: Image.Image, name: str, kind: str, text: str, top, bottom, ink) -> None:
    w, h = img.size
    draw = ImageDraw.Draw(img)

    # 右下角一个几乎同色的超大圆弧，制造层次但不抢戏
    rr = int(min(w, h) * 0.92)
    cx, cy = w * 1.02, h * 1.04
    draw.ellipse([cx - rr, cy - rr, cx + rr, cy + rr],
                 fill=tuple(min(255, int(a * 0.5 + b * 0.5)) for a, b in zip(bottom, top)))

    label = (text or "").strip()
    sub, defaulted = "", False
    if kind == "banner" and len(label) < 4:
        # banner 是首屏门面，测试库里的标题常是 "hh2" 之类的短串，换一句默认文案
        label, defaulted = "精选好物", True
    if kind == "detail":
        sub = "商品详情"
    elif defaulted:
        sub = "限时特惠 · 全场低至五折"
    if not label:
        label = "商品图片" if kind in ("goods", "gallery", "detail", "order_goods") else "图片"

    margin = int(w * 0.12)
    box_w, box_h = w - margin * 2, int(h * 0.50)
    font, lines = fit_font(draw, label, FONT_BOLD, box_w, box_h,
                           max_lines=2 if h < 420 else 3,
                           hi=max(20, int(min(w, h) * 0.15)))

    line_h = int(font.size * 1.30)
    total = line_h * len(lines) + (int(font.size * 0.66) if sub else 0)
    y = h / 2 - total / 2 + line_h / 2
    for ln in lines:
        draw.text((w / 2, y), ln, font=font, fill=ink, anchor="mm")
        y += line_h
    if sub:
        sfont = ImageFont.truetype(FONT_REGULAR, max(12, int(font.size * 0.42)))
        draw.text((w / 2, y - line_h / 2 + font.size * 0.34), sub, font=sfont,
                  fill=tuple(int(c * 0.50 + 255 * 0.50) for c in ink), anchor="mm")

    # 左下角极淡水印
    if w >= 300:
        wfont = ImageFont.truetype(FONT_REGULAR, max(11, int(min(w, h) * 0.028)))
        mark = tuple(min(255, int(c * 0.26 + 255 * 0.74)) for c in ink)
        draw.text((margin, int(h - margin * 0.8)), "zeroai-mall", font=wfont, fill=mark, anchor="lm")


def build(name: str, kind: str, text: str, out_dir: Path) -> None:
    size = SIZES.get(kind, (800, 800))
    top, bottom, ink = palette(name)
    img = gradient(size, top, bottom)
    if kind in ROUND_KINDS:
        draw_round_icon(img, name, text, ink)
    else:
        draw_card(img, name, kind, text, top, bottom, ink)
    img.save(out_dir / name, format="WEBP", quality=84, method=4)


def _worker(job: tuple[str, str, str, str]) -> str:
    """多进程子任务的入口（必须是模块级函数，Windows spawn 才能序列化）。"""
    name, kind, text, out_dir = job
    build(name, kind, text, Path(out_dir))
    return name


# ---------------------------------------------------------------- 入口
def main() -> int:
    ap = argparse.ArgumentParser(description="生成 /upload/** 占位图")
    ap.add_argument("--out", default=os.environ.get("UPLOAD_DIR", r"E:/zeroai-mall/webp"),
                    help="输出目录，默认取 UPLOAD_DIR 或 E:/zeroai-mall/webp")
    ap.add_argument("--force", action="store_true", help="覆盖已存在的文件")
    ap.add_argument("--dry-run", action="store_true", help="只统计，不写盘")
    ap.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) - 1),
                    help="并发生成进程数，1 表示串行")
    args = ap.parse_args()

    for f in (FONT_REGULAR, FONT_BOLD):
        if not Path(f).exists():
            print(f"[x] 缺少字体 {f}", file=sys.stderr)
            return 1

    print("[1/3] 读取数据库中的图片引用 ...")
    mapping = collect()
    if not mapping:
        print("[x] 没有查到任何 /upload 引用，确认数据库已导入", file=sys.stderr)
        return 1

    by_kind: dict[str, int] = {}
    for kind, _ in mapping.values():
        by_kind[kind] = by_kind.get(kind, 0) + 1
    print(f"      共 {len(mapping)} 个唯一文件")
    for kind in sorted(by_kind, key=lambda k: -by_kind[k]):
        print(f"        {kind:16s} {by_kind[kind]:4d}  {SIZES.get(kind, (800, 800))}")

    out_dir = Path(args.out)
    todo = sorted(n for n in mapping if args.force or not (out_dir / n).exists())
    if args.dry_run:
        print(f"[2/3] --dry-run：待生成 {len(todo)} 个（已存在 {len(mapping) - len(todo)} 个）")
        print(f"      输出目录 {out_dir}")
        return 0

    out_dir.mkdir(parents=True, exist_ok=True)
    jobs = [(n, mapping[n][0], mapping[n][1], str(out_dir)) for n in todo]
    print(f"[2/3] 写入 {out_dir}（待生成 {len(jobs)} 个，{args.jobs} 进程）...")

    if args.jobs > 1 and len(jobs) > 32:
        from concurrent.futures import ProcessPoolExecutor, as_completed
        done = 0
        with ProcessPoolExecutor(max_workers=args.jobs) as pool:
            futures = [pool.submit(_worker, job) for job in jobs]
            for fut in as_completed(futures):
                fut.result()
                done += 1
                if done % 500 == 0 or done == len(jobs):
                    print(f"        {done}/{len(jobs)}")
    else:
        for i, job in enumerate(jobs, 1):
            _worker(job)
            if i % 100 == 0 or i == len(jobs):
                print(f"        {i}/{len(jobs)}")

    total = len(list(out_dir.glob("*.webp")))
    size_mb = sum(p.stat().st_size for p in out_dir.glob("*.webp")) / 1024 / 1024
    print(f"[3/3] 完成：目录下共 {total} 个 webp，{size_mb:.1f} MB")
    print(f"      重启 zeroai-mobile-api 后即可访问 http://localhost:82/upload/<name>.webp")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
