#!/usr/bin/env python3
"""售后语料规范化：原始爬取文档 → 可入库的知识文档。

做四件事，每一件都对应一个明确的入库约束：

1. **去品牌**：把平台名、内部工作台名、商家协同术语替换成本商城的通用表述。
   只替换「字面品牌」，涉及京豆/白条/PLUS/京享值这类**本商城不存在的账户体系**时，
   整块内容直接剔除——把京东的积分规则改写成「本商城积分」等于凭空编造政策。
2. **去图片**：本期只做文字。图片引用整行删除，表格单元格里的图变成空单元格。
3. **表格结构化**：`| A | B |` 转成 `- 表头A：值；表头B：值`。
   现有 KnowledgeChunker 按行切块，原始表格行又长又含 `|`，会被切成半行残块。
4. **补 frontmatter**：KnowledgeCatalog 的硬性要求，缺字段整篇拒收。

用法：
    python3 scripts/agent/knowledge/normalize_aftersale_docs.py \
        --src docs/knowledge_source/after \
        --out zeroai-ai-starter/src/main/resources/knowledge

脚本是幂等的：重复执行覆盖同名输出；不删除输出目录里的其它文档。
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

# 平台账户体系：出现即整块剔除。这些概念本商城没有对应实现，
# 保留或改写都会让模型承诺无法履约的政策。
BANNED_BLOCK_TERMS = (
    "京豆", "白条", "PLUS", "plus会员", "京享", "京东金融", "京东支付",
    "京东余额", "E卡", "礼品卡", "保险经纪", "开放平台", "京东服务+",
    "京东自营安装", "厂家安装", "京东物流", "达达", "京准通", "京麦", "保险赔付",
)

# 过滤后仍需修正的表述。全部是「原文依赖平台机制、删掉机制后句子会失真」的位置。
CLEANUPS = (
    (r"https?://\S+", ""),
    (r"（[^）]*魔镜等级[^）]*）", ""),
    (r"魔镜等级、", ""),
    (r"（不包含封闭平台等非主站toc订单）", ""),
    (r"以本商城余额方式向您返还运费首重赔付款", "按首重赔付限额向您补偿运费"),
    (r"补贴首重运费到本商城余额", "补贴首重运费"),
    (r"本商城快递", "商城配送"),
    (r"我的本商城", "我的"),
    (r"自营模式下（不含厂直、全渠道、一号店、pop）", "商城自营模式下"),
    (r"平台政策", "商城政策"),
    (r"上们取件", "上门取件"),
    (r"即9月4日后7天内", "管控期结束后7天内"),
    (r"（即9月11日前）", ""),
    (r"及PLUS(的情况)?", ""),
    (r"\([^)]*[｡･ωﾉ♡◍•ᴗ❤]{2,}[^)]*\)", ""),
    (r"（[^）]*[｡･ωﾉ♡◍•ᴗ❤]{2,}[^）]*）", ""),
    (r"魔镜等级S2及以上的本商城优质用户", "本商城优质用户"),
    (r"魔镜等级在S2及以上的本商城用户", "本商城用户"),
    (r"本商城余额方式", "原支付方式"),
    (r"返还到本商城余额", "返还至原支付账户"),
    (r"本商城余额", "原支付账户"),
    (r"[｡-ﾟ♡♥❤☆★▽◍•ᴗﾉ]", ""),
    (r"^[：:]\s*（详）\s*[，,]\s*$", ""),
    (r"^[：:，,、；;（）()\s]{1,8}$", ""),
)

# 图片链接被删后残留的「跨文档引用标签」，单独成行没有语义，直接丢弃。
DROP_LINES = {
    "返件方式及运费规则", "价保问题", "家电家居安装", "售后问题申请",
    "退款到账时效及路径", "用户售后功能介绍", "售后政策介绍",
}

# 源文档结构本身残缺（编号断裂、图片替代了正文），脚本产出不可用，改为人工整理。
MANUAL_DOCS = {"直通车-售后相关-少退款处理思路.md"}

# 表头出现这些词时整表丢弃：行值全部由平台会员体系决定，留下任何一行都是断章取义。
HEADER_DROP_TERMS = ("客户级别", "会员等级")

# 字面品牌与内部术语的替换表。顺序敏感：长词必须先于短词。
REPLACEMENTS = (
    ("直通车-售后相关-", ""),
    ("直通车-订单操作-", ""),
    ("售后直通车", "售后快捷通道"),
    ("直通车", ""),
    ("京东自营", "商城自营"),
    ("京东平台", "本商城"),
    ("京东物流", "商城配送"),
    ("京东服务+", "商城安装服务"),
    ("京东客服", "商城客服"),
    ("京东", "本商城"),
    ("咚咚工作台", "客服工作台"),
    ("咚咚客服助手", "客服助手"),
    ("咚咚", "在线客服"),
    ("供应商服务单", "商家服务单"),
    ("供应商", "商家"),
    ("自营供应商", "商家"),
    ("自备件库", "备件库"),
    ("平台客服", "商城客服"),
    ("joyspace", ""),
    ("360buyimg", ""),
)

# 文档映射：源文件名 → (输出文件名, sourceId, 标题, 分类, tags)
DOCS = {
    "直通车-售后相关-售后政策介绍.md": (
        "kb-aftersale-policy.md", "kb-aftersale-policy", "售后政策介绍", "after-sale",
        "售后政策,三包,质保期,七天无理由,退货,换货,维修,退换修,只换不修,随心换,特色服务",
    ),
    "直通车-售后相关-售后政策咨询.md": (
        "kb-aftersale-policy-consult.md", "kb-aftersale-policy-consult", "售后政策咨询", "after-sale",
        "售后咨询,政策判断,话术,客服口径",
    ),
    "直通车-售后相关-售后问题申请.md": (
        "kb-aftersale-request.md", "kb-aftersale-request", "售后问题申请", "after-sale",
        "申请售后,售后入口,提交申请,审核,处理思路",
    ),
    "直通车-售后相关-审核处理催促.md": (
        "kb-aftersale-audit-urge.md", "kb-aftersale-audit-urge", "售后审核催促", "after-sale",
        "审核慢,催促,审核时效,加急,处理进度",
    ),
    "直通车-售后相关-审核结果不认可.md": (
        "kb-aftersale-audit-dispute.md", "kb-aftersale-audit-dispute", "审核结果不认可", "after-sale",
        "审核不通过,审核关闭,不认可,申诉,重新申请",
    ),
    "直通车-售后相关-家电家居安装.md": (
        "kb-aftersale-installation.md", "kb-aftersale-installation", "家电家居安装", "after-sale",
        "安装,预约安装,安装收费,改约,取消安装,安装进度,送装一体",
    ),
    "直通车-售后相关-少退款处理思路.md": (
        "kb-aftersale-short-refund.md", "kb-aftersale-short-refund", "少退款处理", "after-sale",
        "少退款,退款金额不对,差额,运费,优惠券分摊,退少",
    ),
    "直通车-售后相关-政策管控商品无法寄出.md": (
        "kb-aftersale-restricted-goods.md", "kb-aftersale-restricted-goods", "管控商品无法寄出", "after-sale",
        "管控商品,无法寄出,上门取件暂停,寄回超时,禁运",
    ),
    "直通车-售后相关-用户售后功能介绍.md": (
        "kb-aftersale-user-guide.md", "kb-aftersale-user-guide", "用户售后功能说明", "after-sale",
        "售后入口,申请入口,售后进度,自助按钮,APP,PC",
    ),
    "直通车-售后相关-返件方式及运费规则.md": (
        "kb-aftersale-return-shipping.md", "kb-aftersale-return-shipping", "返件方式及运费规则", "after-sale",
        "返件,寄回,运费,上门取件,自寄,运费赔付,首重",
    ),
    "直通车-售后相关-退款到账时效及路径.md": (
        "kb-aftersale-refund-timing.md", "kb-aftersale-refund-timing", "退款到账时效及路径", "after-sale",
        "退款到账,多久到账,退款路径,原路退回,银行卡注销,退款时效",
    ),
    "直通车-售后相关-免费上门退换权益介绍.md": (
        "kb-aftersale-free-pickup.md", "kb-aftersale-free-pickup", "免费上门退换", "after-sale",
        "免费上门,上门退换,取件,退换货,服务标识,垫付运费",
    ),
    "直通车-订单操作-取消订单相关.md": (
        "kb-order-cancel.md", "kb-order-cancel", "取消订单", "order",
        "取消订单,未发货取消,已发货拒收,取消原因,退款",
    ),
}

# 整篇不产出：内容全部是内部工作台与商家协同操作，没有对外政策口径。
SKIP_DOCS = {"直通车-售后相关-咚咚服务单备注.md", "README.md"}

HEADING_RE = re.compile(r"^(#{1,6})\s*(.*)$")
IMAGE_RE = re.compile(r"!\[[^\]]*\]\([^)]*\)")
LINK_RE = re.compile(r"\[([^\]]*)\]\([^)]*\)")
TABLE_SEP_RE = re.compile(r"^\|?[\s:|-]+\|[\s:|-]*$")


def strip_inline(text: str) -> str:
    """去图片、去链接地址、去强调符号，保留可读文本。"""
    text = IMAGE_RE.sub("", text)
    text = LINK_RE.sub(r"\1", text)
    text = text.replace("**", "").replace("__", "")
    # 单星号只用于强调，成对出现时才删，避免误伤乘号等场景
    text = re.sub(r"(?<!\*)\*(?!\*)", "", text)
    return text.strip()


def has_banned(text: str) -> bool:
    return any(term in text for term in BANNED_BLOCK_TERMS)


def replace_brand(text: str) -> str:
    for old, new in REPLACEMENTS:
        text = text.replace(old, new)
    return text


def apply_cleanups(text: str) -> str:
    """执行针对性修正，返回清洗后的文本（可能为空串）。"""
    for pattern, replacement in CLEANUPS:
        text = re.sub(pattern, replacement, text)
    return text.strip()


def split_row(line: str) -> list[str]:
    """拆表格行。原始语料没有单元格内转义竖线，直接按 | 切即可。"""
    cells = line.strip().strip("|").split("|")
    return [strip_inline(cell) for cell in cells]


def clean_cell(text: str) -> str:
    """表格单元格清洗：去图片/链接 → 换品牌词 → 针对性修正 → 统一项目符号。"""
    text = apply_cleanups(replace_brand(strip_inline(text)))
    text = re.sub(r"[●■◆▪]", "；", text)
    text = re.sub(r"[○◦]", "；", text)
    text = re.sub(r"；{2,}", "；", text)
    return text.strip("； ").strip()


def convert_table(rows: list[str]) -> list[str]:
    """表格转「表头：值」的列表；保留表头语义，避免切块后丢列名。"""
    if not rows:
        return []
    header = [clean_cell(cell) for cell in split_row(rows[0])]
    # 表头就带平台账户体系（如「拒收扣除京豆规则」）时整表丢弃：
    # 类目虽然通用，但每一行的规则值都依赖那套账户体系，留下表头只会得到半张表。
    if any(has_banned(cell) for cell in header) or any(term in cell for cell in header for term in HEADER_DROP_TERMS):
        return []
    body = [r for r in rows[1:] if not TABLE_SEP_RE.match(r.strip())]
    out: list[str] = []
    for row in body:
        cells = [clean_cell(cell) for cell in split_row(row)]
        if not any(cells):
            continue
        pairs = []
        for index, cell in enumerate(cells):
            if not cell or has_banned(cell):
                continue
            key = header[index] if index < len(header) else ""
            # 表头为空、或单元格本身就是长句时，不强行加「表头：」前缀
            if key and len(key) <= 12 and not cell.startswith(key):
                pairs.append(f"{key}：{cell}")
            else:
                pairs.append(cell)
        if not pairs:
            continue
        line = "；".join(pairs)
        if has_banned(line):
            continue
        out.append("- " + line)
    return out


def normalize_body(raw: str) -> list[str]:
    """逐行处理，保持标题、列表、段落的原始顺序。"""
    lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    out: list[str] = []
    table_buffer: list[str] = []
    in_frontmatter = raw.startswith("---")

    def flush_table() -> None:
        nonlocal table_buffer
        if table_buffer:
            out.extend(convert_table(table_buffer))
            table_buffer = []
            out.append("")
        return

    for index, raw_line in enumerate(lines):
        line = raw_line.strip()

        # 跳过原始 frontmatter（title/url/crawled_at 等），输出层会重新生成
        if in_frontmatter:
            if index == 0:
                continue
            if line == "---":
                in_frontmatter = False
            continue

        if line.startswith("|"):
            table_buffer.append(line)
            continue
        flush_table()

        if not line:
            continue

        # 保密提示、来源声明、纯分隔线：与政策无关，进索引只会成为噪声
        if line.startswith(">") or set(line) <= {"-", "*", "_", " "}:
            continue
        if "仅供" in line and ("查阅" in line or "外传" in line):
            continue

        heading = HEADING_RE.match(line)
        if heading:
            title = apply_cleanups(replace_brand(strip_inline(heading.group(2)))).strip()
            if not title:
                continue
            if has_banned(title):
                # 标题命中平台机制（如「PLUS 权益」）：整节内容由后续行过滤，
                # 这里只丢弃标题本身，避免出现没有正文的孤立标题
                continue
            out.append(f"{'#' * len(heading.group(1))} {title}")
            out.append("")
            continue

        text = apply_cleanups(replace_brand(strip_inline(line)))
        if not text or text in DROP_LINES:
            continue
        if has_banned(text):
            continue

        # 统一项目符号，保证切块时每条规则独立成行
        text = re.sub(r"^[●■◆▪]\s*", "- ", text)
        text = re.sub(r"^[○◦]\s*", "  - ", text)
        # 已经是短横线列表的保留；纯序号列表保持原样
        out.append(text)

    flush_table()
    return out


def prune_empty_headings(lines: list[str]) -> list[str]:
    """删除「没有任何正文、后面直接接同级或更高级标题」的悬空标题。

    图片被整行删除后，像「## 3、申请详情页」这种只有截图的叶子小节会变成空壳；
    保留它只会让切块器在这里白白断一次。父标题（后面跟更深层级标题）保留，
    因为它们承担结构作用。
    """
    out: list[str] = []
    for index, line in enumerate(lines):
        heading = HEADING_RE.match(line)
        if not heading:
            out.append(line)
            continue
        level = len(heading.group(1))
        has_content = False
        next_level: int | None = None
        for follow in lines[index + 1:]:
            nested = HEADING_RE.match(follow)
            if nested:
                next_level = len(nested.group(1))
                break
            if follow.strip():
                has_content = True
                break
        if has_content or (next_level is not None and next_level > level):
            out.append(line)
    return out


def prune_dangling_prompts(lines: list[str]) -> list[str]:
    """删除「以冒号结尾但后面没有内容」的引导句。

    图片整行删除后，像「前台展示标识&服务楼层文案：」这种只负责引出截图的句子
    会直接顶到下一个标题，留在索引里是一条无答案的半句。
    """
    out: list[str] = []
    for index, line in enumerate(lines):
        stripped = line.strip()
        if stripped.endswith(("：", ":")) and len(stripped) < 40:
            following = next((item for item in lines[index + 1:] if item.strip()), "")
            if not following or following.startswith("#"):
                continue
        out.append(line)
    return out


def collapse(lines: list[str]) -> str:
    """压缩连续空行，去掉文末空行。"""
    result: list[str] = []
    for line in lines:
        if not line.strip():
            if result and result[-1] == "":
                continue
            result.append("")
        else:
            result.append(line.rstrip())
    while result and result[-1] == "":
        result.pop()
    return "\n".join(result)


def source_title(raw: str) -> str:
    """从原始 frontmatter 读取标题，用于识别正文首行的标题重复。"""
    if not raw.startswith("---"):
        return ""
    end = raw.find("\n---", 3)
    if end < 0:
        return ""
    for line in raw[3:end].split("\n"):
        if line.strip().startswith("title:"):
            return apply_cleanups(replace_brand(line.split(":", 1)[1].strip()))
    return ""


def build_document(src: Path, meta: tuple[str, str, str, str, str]) -> tuple[str, str]:
    filename, source_id, title, category, tags = meta
    raw = src.read_text(encoding="utf-8")
    original_title = source_title(raw)
    lines = prune_dangling_prompts(prune_empty_headings(normalize_body(raw)))
    # 正文首行常见「标题重复一遍」，与 frontmatter 的 title 重复，去掉可省一块噪声
    while lines and not lines[0].strip():
        lines.pop(0)
    if lines:
        first = lines[0].strip().lstrip("#").strip()
        if (first == title or (original_title and first == original_title)
                or (title in first and len(first) <= len(title) + 4)):
            lines.pop(0)
    body = collapse(lines)
    if len(body) < 200:
        raise ValueError(f"{src.name} 规范化后正文过短（{len(body)} 字），请检查过滤规则")

    frontmatter = "\n".join([
        "---",
        f"sourceId: {source_id}",
        f"title: {title}",
        f"category: {category}",
        "version: 1",
        "updatedAt: 2026-11-24",
        "visibility: public",
        f"tags: {tags}",
        "---",
    ])
    return filename, frontmatter + "\n" + body + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--src", default="docs/knowledge_source/after")
    parser.add_argument("--out", default="zeroai-ai-starter/src/main/resources/knowledge")
    args = parser.parse_args()

    src_dir = Path(args.src)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    written = 0
    for source_name, meta in DOCS.items():
        if source_name in MANUAL_DOCS:
            print(f"[manual] {source_name}（源结构残缺，由人工整理产出）")
            continue
        source = src_dir / source_name
        if not source.exists():
            raise SystemExit(f"缺少源文件：{source}")
        filename, text = build_document(source, meta)
        (out_dir / filename).write_text(text, encoding="utf-8")
        print(f"[ok] {source_name} -> {filename} ({len(text)} 字)")
        written += 1

    for skipped in SKIP_DOCS:
        if (src_dir / skipped).exists():
            print(f"[skip] {skipped}（内部协同文档，不产出）")

    print(f"完成：{written} 篇")


if __name__ == "__main__":
    main()
