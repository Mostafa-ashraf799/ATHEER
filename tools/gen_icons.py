#!/usr/bin/env python3
"""
مولّد الأيقونات: مصدر الحقيقة هو ملفات SVG في design/icons/*.svg
  python3 tools/gen_icons.py seed   -> بيكتب ملفات SVG الأولية (مرة واحدة)
  python3 tools/gen_icons.py        -> بيقرأ الـ SVG ويولّد app/.../ui/AIcons.kt
عدّل أي SVG (24x24، stroke بعرض 2) وشغّل الأمر الأخير.
الصيغة: عناصر <path d="..."/> ؛ ولو عايز تعبئة: <path d="..." fill="currentColor" stroke="none"/>
"""
import re, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SVG_DIR = ROOT / "design" / "icons"
KT_OUT = ROOT / "app/src/main/java/com/binarybeast/chatapp/ui/AIcons.kt"
MIRROR = {"Send", "Reply", "ArrowBack", "ArrowForward", "CallMade", "CallReceived", "CallMissed", "Undo"}

def circle(cx, cy, r):
    return f"M{cx - r} {cy}a{r} {r} 0 1 0 {2 * r} 0a{r} {r} 0 1 0 {-2 * r} 0"

F = "F:"  # بادئة = مسار معبّأ
ICONS = {
 "Close": ["M18 6L6 18", "M6 6l12 12"],
 "Add": ["M12 5v14", "M5 12h14"],
 "Videocam": ["M23 7l-7 5 7 5V7z", "M3 5h11a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z"],
 "VideocamOff": ["M16 16v1a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h2m5.66 0H14a2 2 0 0 1 2 2v3.34l1 1L23 7v10", "M1 1l22 22"],
 "Delete": ["M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2", "M10 11v6", "M14 11v6"],
 "Call": ["M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"],
 "CallEnd": ["M10.68 13.31a16 16 0 0 0 3.41 2.6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7 2 2 0 0 1 1.72 2v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.42 19.42 0 0 1-3.33-2.67m-2.67-3.34a19.79 19.79 0 0 1-3.07-8.63A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91", "M23 1L1 23"],
 "CameraAlt": ["M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z", circle(12, 13, 4)],
 "Cameraswitch": ["M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z", "M8.5 14a4 4 0 0 1 6.5-2.5", "M15 8.5v3h-3", "M15.5 12a4 4 0 0 1-6.5 2.5", "M9 17.5v-3h3"],
 "Reply": ["M9 14L4 9l5-5", "M20 20v-7a4 4 0 0 0-4-4H4"],
 "ArrowBack": ["M19 12H5", "M12 19l-7-7 7-7"],
 "ArrowForward": ["M5 12h14", "M12 5l7 7-7 7"],
 "PlayCircle": [circle(12, 12, 10), "M10 8l6 4-6 4z"],
 "PhotoLibrary": ["M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", circle(8.5, 8.5, 1.5), "M21 15l-5-5L5 21"],
 "Person": ["M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2", circle(12, 7, 4)],
 "PersonAdd": ["M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2", circle(8.5, 7, 4), "M20 8v6", "M23 11h-6"],
 "Group": ["M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2", circle(9, 7, 4), "M23 21v-2a4 4 0 0 0-3-3.87", "M16 3.13a4 4 0 0 1 0 7.75"],
 "GroupAdd": ["M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2", circle(9, 7, 4), "M20 8v6", "M23 11h-6"],
 "MoreVert": [F + circle(12, 5, 1.6), F + circle(12, 12, 1.6), F + circle(12, 19, 1.6)],
 "Mic": ["M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 19v4", "M8 23h8"],
 "MicOff": ["M1 1l22 22", "M9 9v3a3 3 0 0 0 5.12 2.12M15 9.34V4a3 3 0 0 0-5.94-.6", "M17 16.95A7 7 0 0 1 5 12v-2m14 0v2a7 7 0 0 1-.11 1.23", "M12 19v4", "M8 23h8"],
 "Lock": ["M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4"],
 "LockOpen": ["M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 9.9-1"],
 "Edit": ["M12 20h9", "M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"],
 "Done": ["M5 12l5 5L20 7"],
 "DoneAll": ["M2 13l4 4L16 7", "M11 15l2 2L22 8"],
 "Chat": ["M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"],
 "Block": [circle(12, 12, 10), "M4.93 4.93l14.14 14.14"],
 "AttachFile": ["M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"],
 "Send": ["M22 2L11 13", "M22 2l-7 20-4-9-9-4 20-7z"],
 "VolumeUp": ["M11 5L6 9H2v6h4l5 4V5z", "M19.07 4.93a10 10 0 0 1 0 14.14", "M15.54 8.46a5 5 0 0 1 0 7.07"],
 "VolumeOff": ["M11 5L6 9H2v6h4l5 4V5z", "M23 9l-6 6", "M17 9l6 6"],
 "Undo": ["M3 7v6h6", "M21 17a9 9 0 0 0-9-9 9 9 0 0 0-6 2.3L3 13"],
 "Archive": ["M21 8v13H3V8", "M1 3h22v5H1z", "M10 12h4"],
 "Unarchive": ["M21 8v13H3V8", "M1 3h22v5H1z", "M12 17v-6", "M9 14l3-3 3 3"],
 "Tune": ["M4 21v-7", "M4 10V3", "M12 21v-9", "M12 8V3", "M20 21v-5", "M20 12V3", "M1 14h6", "M9 8h6", "M17 16h6"],
 "TextFields": ["M4 7V4h16v3", "M9 20h6", "M12 4v16"],
 "Storage": ["M21 5c0 1.66-4.03 3-9 3S3 6.66 3 5s4.03-3 9-3 9 1.34 9 3z", "M21 12c0 1.66-4.03 3-9 3s-9-1.34-9-3", "M3 5v14c0 1.66 4.03 3 9 3s9-1.34 9-3V5"],
 "Search": [circle(11, 11, 8), "M21 21l-4.35-4.35"],
 "RotateRight": ["M23 4v6h-6", "M20.49 15a9 9 0 1 1-2.12-9.36L23 10"],
 "RotateLeft": ["M1 4v6h6", "M3.51 15a9 9 0 1 0 2.13-9.36L1 10"],
 "Refresh": ["M23 4v6h-6", "M1 20v-6h6", "M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"],
 "PushPin": ["M12 17v5", "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"],
 "Public": [circle(12, 12, 10), "M2 12h20", "M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"],
 "Poll": ["M18 20V10", "M12 20V4", "M6 20v-6"],
 "Pause": ["M6 4h4v16H6z", "M14 4h4v16h-4z"],
 "Notifications": ["M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9", "M13.73 21a2 2 0 0 1-3.46 0"],
 "InsertDriveFile": ["M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z", "M13 2v7h7"],
 "Info": [circle(12, 12, 10), "M12 16v-4", "M12 8h.01"],
 "Flip": ["M12 3v18", "M8 6L3 12l5 6V6z", "M16 6l5 6-5 6V6z"],
 "Crop": ["M6.13 1L6 16a2 2 0 0 0 2 2h15", "M1 6.13L16 6a2 2 0 0 1 2 2v15"],
 "ContentCopy": ["M11 9h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z", "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"],
 "Campaign": ["M3 11v2a1 1 0 0 0 1 1h3l7 4V6L7 10H4a1 1 0 0 0-1 1z", "M17 8a5 5 0 0 1 0 8", "M20.5 5.5a8.5 8.5 0 0 1 0 13"],
 "Brush": ["M9.06 11.9l8.07-8.06a2.85 2.85 0 1 1 4.03 4.03l-8.06 8.08", "M7.07 14.94c-1.66 0-3 1.35-3 3.02 0 1.33-2.5 1.52-2 2.02 1.08 1.1 2.49 2.02 4 2.02 2.2 0 4-1.8 4-4.04a3.01 3.01 0 0 0-3-3.02z"],
 "AutoFixHigh": ["M15 4V2", "M15 16v-2", "M8 9h2", "M20 9h2", "M17.8 11.8L19 13", "M17.8 6.2L19 5", "M3 21l9-9", "M12.2 6.2L11 5"],
 "AutoAwesome": ["M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z", "M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z"],
 "CallMade": ["M7 17L17 7", "M7 7h10v10"],
 "CallReceived": ["M17 7L7 17", "M17 17H7V7"],
 "CallMissed": ["M17 7L7 17", "M7 11v6h6", "M16 3l5 5", "M21 3l-5 5"],
 # للخلفيات والهوية
 "Star": ["M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"],
 "Heart": ["M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"],
 "Smile": [circle(12, 12, 10), "M8 14s1.5 2 4 2 4-2 4-2", "M9 9h.01", "M15 9h.01"],
 "Music": ["M9 18V5l12-2v13", circle(6, 18, 3), circle(18, 16, 3)],
 "Moon": ["M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"],
 "Sun": [circle(12, 12, 5), "M12 1v2", "M12 21v2", "M4.22 4.22l1.42 1.42", "M18.36 18.36l1.42 1.42", "M1 12h2", "M21 12h2", "M4.22 19.78l1.42-1.42", "M18.36 5.64l1.42-1.42"],
 "Cloud": ["M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"],
 "Bolt": ["M13 2L3 14h9l-1 8 10-12h-9l1-8z"],
 "Gift": ["M20 12v10H4V12", "M2 7h20v5H2z", "M12 22V7", "M12 7H7.5a2.5 2.5 0 0 1 0-5C11 2 12 7 12 7z", "M12 7h4.5a2.5 2.5 0 0 0 0-5C13 2 12 7 12 7z"],
 "Coffee": ["M18 8h1a4 4 0 0 1 0 8h-1", "M2 8h16v9a4 4 0 0 1-4 4H6a4 4 0 0 1-4-4V8z", "M6 1v3", "M10 1v3", "M14 1v3"],
}

def svg_of(paths):
    out = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">']
    for p in paths:
        if p.startswith(F):
            out.append(f'  <path d="{p[len(F):]}" fill="currentColor" stroke="none"/>')
        else:
            out.append(f'  <path d="{p}"/>')
    out.append("</svg>")
    return "\n".join(out) + "\n"

def seed():
    SVG_DIR.mkdir(parents=True, exist_ok=True)
    for name, paths in ICONS.items():
        (SVG_DIR / f"{name}.svg").write_text(svg_of(paths), encoding="utf-8")
    print(f"wrote {len(ICONS)} svg files to {SVG_DIR}")

def generate():
    items = []
    for f in sorted(SVG_DIR.glob("*.svg")):
        txt = f.read_text(encoding="utf-8")
        paths = []
        for m in re.finditer(r"<path\b([^>]*)/?>", txt):
            attrs = m.group(1)
            d = re.search(r'\bd="([^"]+)"', attrs).group(1)
            filled = 'fill="currentColor"' in attrs
            paths.append((d, filled))
        items.append((f.stem, paths))
    lines = [
        "package com.binarybeast.chatapp.ui",
        "",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.graphics.SolidColor",
        "import androidx.compose.ui.graphics.StrokeCap",
        "import androidx.compose.ui.graphics.StrokeJoin",
        "import androidx.compose.ui.graphics.vector.ImageVector",
        "import androidx.compose.ui.graphics.vector.PathParser",
        "import androidx.compose.ui.unit.dp",
        "",
        "// ملف مولَّد من design/icons/*.svg بواسطة tools/gen_icons.py — متعدّلوش بإيدك، عدّل الـ SVG وشغّل السكربت.",
        "private fun svgIcon(name: String, mirror: Boolean, vararg paths: Pair<String, Boolean>): ImageVector =",
        "    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirror).apply {",
        "        for ((d, filled) in paths) {",
        "            val nodes = PathParser().parsePathString(d).toNodes()",
        "            if (filled) addPath(nodes, fill = SolidColor(Color.Black))",
        "            else addPath(nodes, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)",
        "        }",
        "    }.build()",
        "",
        "/** كل أيقونات التطبيق (SVG → ImageVector). */",
        "object AIcons {",
    ]
    for name, paths in items:
        mirror = "true" if name in MIRROR else "false"
        body = ", ".join(f'"{d}" to {"true" if f else "false"}' for d, f in paths)
        lines.append(f"    val {name}: ImageVector by lazy {{ svgIcon(\"{name}\", {mirror}, {body}) }}")
    lines.append("}")
    KT_OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"generated {len(items)} icons -> {KT_OUT}")

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "seed":
        seed()
    else:
        generate()
