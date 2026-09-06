#!/usr/bin/env python3
"""Generate values-zh-rTW (Traditional Chinese) from values-zh-rCN.

Hand-maintained conversion for this project's string set: it applies a
multi-char override table first (whole-term fixes that are not 1:1 char
mappings), then a per-character Simplified->Traditional map, then a small
per-string special case. Used to keep zh-rTW in sync with zh-rCN.
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULES = [
    "app/src/main",
    "app/src/debug",
    "data/preferences/src/main",
]

# Whole-term overrides (order matters; applied before char mapping).
MULTI = [
    ("归一化", "正規化"),
    ("后台", "背景"),
    ("菜单", "選單"),
    ("导入", "匯入"),
    ("导出", "匯出"),
    ("保存", "儲存"),
    ("编辑", "編輯"),
    ("复制", "複製"),
    ("删除", "刪除"),
    ("设置", "設定"),
    ("界面", "介面"),
    ("调试", "偵錯"),
    ("手势", "手勢"),
    ("双击", "雙擊"),
    ("范围", "範圍"),
    ("试听", "試聽"),
    ("通过", "透過"),
    ("音频", "音頻"),
    ("应用程式", "應用程式"),  # keep "application" as 應用, not 套用
    ("恢复", "恢復"),
]

# Per-character Simplified -> Traditional map (only differing characters).
CHAR = {
    "双": "雙", "节": "節", "周": "週", "预": "預", "设": "設", "声": "聲",
    "载": "載", "松": "鬆", "时": "時", "采": "採", "样": "樣", "衡": "衡",
    "缓": "緩", "冲": "衝", "区": "區", "图": "圖", "点": "點", "线": "線",
    "数": "數", "条": "條", "张": "張", "换": "換", "负": "負", "关": "關",
    "间": "間", "终": "終", "规": "規", "趋": "趨", "势": "勢", "转": "轉",
    "进": "進", "触": "觸", "发": "發", "变": "變", "类": "類", "过": "過",
    "渐": "漸", "暂": "暫", "无": "無", "强": "強", "于": "於", "调": "調",
    "整": "整", "据": "據", "频": "頻", "唤": "喚", "省": "省", "电": "電",
    "重": "重", "计": "計", "标": "標", "准": "準", "质": "質", "实": "實",
    "弦": "弦", "精": "精", "个": "個", "内": "內", "存": "存", "占": "佔",
    "面": "面", "试": "試", "连": "連", "断": "斷", "开": "開", "则": "則",
    "启": "啟", "动": "動", "应": "應", "程": "程", "式": "式", "听": "聽",
    "景": "景", "导": "導", "致": "致", "请": "請", "将": "將", "池": "池",
    "优": "優", "例": "例", "外": "外", "让": "讓", "续": "續", "确": "確",
    "法": "法", "钟": "鐘", "毫": "毫", "套": "套", "势": "勢", "帮": "幫",
    "编": "編", "辑": "輯", "操": "操", "击": "擊", "处": "處", "增": "增",
    "栏": "欄", "签": "籤", "范": "範", "围": "圍", "天": "天", "中": "中",
    "恢": "恢", "复": "復", "当": "當", "已": "已", "结": "結", "束": "束",
    "显": "顯", "示": "示", "马": "馬",
}

# Exact-string fixes applied after conversion.
SPECIAL = {
    "應用": "套用",  # the "Apply" button; 應用 is kept for "application"
}


def convert(text):
    for s, t in MULTI:
        text = text.replace(s, t)
    out = []
    for ch in text:
        out.append(CHAR.get(ch, ch))
    result = "".join(out)
    if result in SPECIAL:
        return SPECIAL[result]
    return result


def main():
    import re
    pattern = re.compile(r"<string name=\"([^\"]+)\">([^<]*)</string>")
    for mod in MODULES:
        base = os.path.join(ROOT, mod, "res")
        src = os.path.join(base, "values-zh-rCN", "strings.xml")
        dst = os.path.join(base, "values-zh-rTW", "strings.xml")
        if not os.path.isfile(src):
            print("skip (no values-zh-rCN):", mod)
            continue
        with open(src, encoding="utf-8") as f:
            raw = f.read()

        def repl(m):
            return "<string name=\"%s\">%s</string>" % (m.group(1), convert(m.group(2)))

        converted = pattern.sub(repl, raw)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with open(dst, "w", encoding="utf-8") as f:
            f.write(converted)
        print("wrote", dst)


if __name__ == "__main__":
    main()
