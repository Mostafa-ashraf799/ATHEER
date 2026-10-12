package com.binarybeast.chatapp.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// ملف مولَّد من design/icons/*.svg بواسطة tools/gen_icons.py — متعدّلوش بإيدك، عدّل الـ SVG وشغّل السكربت.
private fun svgIcon(name: String, mirror: Boolean, vararg paths: Pair<String, Boolean>): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = mirror).apply {
        for ((d, filled) in paths) {
            val nodes = PathParser().parsePathString(d).toNodes()
            if (filled) addPath(nodes, fill = SolidColor(Color.Black))
            else addPath(nodes, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
    }.build()

/** كل أيقونات التطبيق (SVG → ImageVector). */
object AIcons {
    val Add: ImageVector by lazy { svgIcon("Add", false, "M12 5v14" to false, "M5 12h14" to false) }
    val Archive: ImageVector by lazy { svgIcon("Archive", false, "M21 8v13H3V8" to false, "M1 3h22v5H1z" to false, "M10 12h4" to false) }
    val ArrowBack: ImageVector by lazy { svgIcon("ArrowBack", true, "M19 12H5" to false, "M12 19l-7-7 7-7" to false) }
    val ArrowForward: ImageVector by lazy { svgIcon("ArrowForward", true, "M5 12h14" to false, "M12 5l7 7-7 7" to false) }
    val AttachFile: ImageVector by lazy { svgIcon("AttachFile", false, "M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48" to false) }
    val AutoAwesome: ImageVector by lazy { svgIcon("AutoAwesome", false, "M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z" to false, "M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z" to false) }
    val AutoFixHigh: ImageVector by lazy { svgIcon("AutoFixHigh", false, "M15 4V2" to false, "M15 16v-2" to false, "M8 9h2" to false, "M20 9h2" to false, "M17.8 11.8L19 13" to false, "M17.8 6.2L19 5" to false, "M3 21l9-9" to false, "M12.2 6.2L11 5" to false) }
    val Block: ImageVector by lazy { svgIcon("Block", false, "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0" to false, "M4.93 4.93l14.14 14.14" to false) }
    val Bolt: ImageVector by lazy { svgIcon("Bolt", false, "M13 2L3 14h9l-1 8 10-12h-9l1-8z" to false) }
    val Brush: ImageVector by lazy { svgIcon("Brush", false, "M9.06 11.9l8.07-8.06a2.85 2.85 0 1 1 4.03 4.03l-8.06 8.08" to false, "M7.07 14.94c-1.66 0-3 1.35-3 3.02 0 1.33-2.5 1.52-2 2.02 1.08 1.1 2.49 2.02 4 2.02 2.2 0 4-1.8 4-4.04a3.01 3.01 0 0 0-3-3.02z" to false) }
    val Call: ImageVector by lazy { svgIcon("Call", false, "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z" to false) }
    val CallEnd: ImageVector by lazy { svgIcon("CallEnd", false, "M10.68 13.31a16 16 0 0 0 3.41 2.6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7 2 2 0 0 1 1.72 2v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.42 19.42 0 0 1-3.33-2.67m-2.67-3.34a19.79 19.79 0 0 1-3.07-8.63A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91" to false, "M23 1L1 23" to false) }
    val CallMade: ImageVector by lazy { svgIcon("CallMade", true, "M7 17L17 7" to false, "M7 7h10v10" to false) }
    val CallMissed: ImageVector by lazy { svgIcon("CallMissed", true, "M17 7L7 17" to false, "M7 11v6h6" to false, "M16 3l5 5" to false, "M21 3l-5 5" to false) }
    val CallReceived: ImageVector by lazy { svgIcon("CallReceived", true, "M17 7L7 17" to false, "M17 17H7V7" to false) }
    val CameraAlt: ImageVector by lazy { svgIcon("CameraAlt", false, "M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" to false, "M8 13a4 4 0 1 0 8 0a4 4 0 1 0 -8 0" to false) }
    val Cameraswitch: ImageVector by lazy { svgIcon("Cameraswitch", false, "M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" to false, "M8.5 14a4 4 0 0 1 6.5-2.5" to false, "M15 8.5v3h-3" to false, "M15.5 12a4 4 0 0 1-6.5 2.5" to false, "M9 17.5v-3h3" to false) }
    val Campaign: ImageVector by lazy { svgIcon("Campaign", false, "M3 11v2a1 1 0 0 0 1 1h3l7 4V6L7 10H4a1 1 0 0 0-1 1z" to false, "M17 8a5 5 0 0 1 0 8" to false, "M20.5 5.5a8.5 8.5 0 0 1 0 13" to false) }
    val Chat: ImageVector by lazy { svgIcon("Chat", false, "M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z" to false) }
    val Close: ImageVector by lazy { svgIcon("Close", false, "M18 6L6 18" to false, "M6 6l12 12" to false) }
    val Cloud: ImageVector by lazy { svgIcon("Cloud", false, "M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z" to false) }
    val Coffee: ImageVector by lazy { svgIcon("Coffee", false, "M18 8h1a4 4 0 0 1 0 8h-1" to false, "M2 8h16v9a4 4 0 0 1-4 4H6a4 4 0 0 1-4-4V8z" to false, "M6 1v3" to false, "M10 1v3" to false, "M14 1v3" to false) }
    val ContentCopy: ImageVector by lazy { svgIcon("ContentCopy", false, "M11 9h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z" to false, "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" to false) }
    val Crop: ImageVector by lazy { svgIcon("Crop", false, "M6.13 1L6 16a2 2 0 0 0 2 2h15" to false, "M1 6.13L16 6a2 2 0 0 1 2 2v15" to false) }
    val Delete: ImageVector by lazy { svgIcon("Delete", false, "M3 6h18" to false, "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" to false, "M10 11v6" to false, "M14 11v6" to false) }
    val Done: ImageVector by lazy { svgIcon("Done", false, "M5 12l5 5L20 7" to false) }
    val DoneAll: ImageVector by lazy { svgIcon("DoneAll", false, "M2 13l4 4L16 7" to false, "M11 15l2 2L22 8" to false) }
    val Edit: ImageVector by lazy { svgIcon("Edit", false, "M12 20h9" to false, "M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z" to false) }
    val Flip: ImageVector by lazy { svgIcon("Flip", false, "M12 3v18" to false, "M8 6L3 12l5 6V6z" to false, "M16 6l5 6-5 6V6z" to false) }
    val Gift: ImageVector by lazy { svgIcon("Gift", false, "M20 12v10H4V12" to false, "M2 7h20v5H2z" to false, "M12 22V7" to false, "M12 7H7.5a2.5 2.5 0 0 1 0-5C11 2 12 7 12 7z" to false, "M12 7h4.5a2.5 2.5 0 0 0 0-5C13 2 12 7 12 7z" to false) }
    val Group: ImageVector by lazy { svgIcon("Group", false, "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" to false, "M5 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0" to false, "M23 21v-2a4 4 0 0 0-3-3.87" to false, "M16 3.13a4 4 0 0 1 0 7.75" to false) }
    val GroupAdd: ImageVector by lazy { svgIcon("GroupAdd", false, "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" to false, "M5 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0" to false, "M20 8v6" to false, "M23 11h-6" to false) }
    val Heart: ImageVector by lazy { svgIcon("Heart", false, "M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z" to false) }
    val Info: ImageVector by lazy { svgIcon("Info", false, "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0" to false, "M12 16v-4" to false, "M12 8h.01" to false) }
    val InsertDriveFile: ImageVector by lazy { svgIcon("InsertDriveFile", false, "M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z" to false, "M13 2v7h7" to false) }
    val Lock: ImageVector by lazy { svgIcon("Lock", false, "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z" to false, "M7 11V7a5 5 0 0 1 10 0v4" to false) }
    val LockOpen: ImageVector by lazy { svgIcon("LockOpen", false, "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z" to false, "M7 11V7a5 5 0 0 1 9.9-1" to false) }
    val Mic: ImageVector by lazy { svgIcon("Mic", false, "M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z" to false, "M19 10v2a7 7 0 0 1-14 0v-2" to false, "M12 19v4" to false, "M8 23h8" to false) }
    val MicOff: ImageVector by lazy { svgIcon("MicOff", false, "M1 1l22 22" to false, "M9 9v3a3 3 0 0 0 5.12 2.12M15 9.34V4a3 3 0 0 0-5.94-.6" to false, "M17 16.95A7 7 0 0 1 5 12v-2m14 0v2a7 7 0 0 1-.11 1.23" to false, "M12 19v4" to false, "M8 23h8" to false) }
    val Moon: ImageVector by lazy { svgIcon("Moon", false, "M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" to false) }
    val MoreVert: ImageVector by lazy { svgIcon("MoreVert", false, "M10.4 5a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0" to true, "M10.4 12a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0" to true, "M10.4 19a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0" to true) }
    val Music: ImageVector by lazy { svgIcon("Music", false, "M9 18V5l12-2v13" to false, "M3 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0" to false, "M15 16a3 3 0 1 0 6 0a3 3 0 1 0 -6 0" to false) }
    val Notifications: ImageVector by lazy { svgIcon("Notifications", false, "M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" to false, "M13.73 21a2 2 0 0 1-3.46 0" to false) }
    val Pause: ImageVector by lazy { svgIcon("Pause", false, "M6 4h4v16H6z" to false, "M14 4h4v16h-4z" to false) }
    val Person: ImageVector by lazy { svgIcon("Person", false, "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" to false, "M8 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0" to false) }
    val PersonAdd: ImageVector by lazy { svgIcon("PersonAdd", false, "M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" to false, "M4.5 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0" to false, "M20 8v6" to false, "M23 11h-6" to false) }
    val PhotoLibrary: ImageVector by lazy { svgIcon("PhotoLibrary", false, "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z" to false, "M7.0 8.5a1.5 1.5 0 1 0 3.0 0a1.5 1.5 0 1 0 -3.0 0" to false, "M21 15l-5-5L5 21" to false) }
    val PlayCircle: ImageVector by lazy { svgIcon("PlayCircle", false, "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0" to false, "M10 8l6 4-6 4z" to false) }
    val Poll: ImageVector by lazy { svgIcon("Poll", false, "M18 20V10" to false, "M12 20V4" to false, "M6 20v-6" to false) }
    val Public: ImageVector by lazy { svgIcon("Public", false, "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0" to false, "M2 12h20" to false, "M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" to false) }
    val PushPin: ImageVector by lazy { svgIcon("PushPin", false, "M12 17v5" to false, "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z" to false) }
    val Refresh: ImageVector by lazy { svgIcon("Refresh", false, "M23 4v6h-6" to false, "M1 20v-6h6" to false, "M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" to false) }
    val Reply: ImageVector by lazy { svgIcon("Reply", true, "M9 14L4 9l5-5" to false, "M20 20v-7a4 4 0 0 0-4-4H4" to false) }
    val RotateLeft: ImageVector by lazy { svgIcon("RotateLeft", false, "M1 4v6h6" to false, "M3.51 15a9 9 0 1 0 2.13-9.36L1 10" to false) }
    val RotateRight: ImageVector by lazy { svgIcon("RotateRight", false, "M23 4v6h-6" to false, "M20.49 15a9 9 0 1 1-2.12-9.36L23 10" to false) }
    val Search: ImageVector by lazy { svgIcon("Search", false, "M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0" to false, "M21 21l-4.35-4.35" to false) }
    val Send: ImageVector by lazy { svgIcon("Send", true, "M22 2L11 13" to false, "M22 2l-7 20-4-9-9-4 20-7z" to false) }
    val Smile: ImageVector by lazy { svgIcon("Smile", false, "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0" to false, "M8 14s1.5 2 4 2 4-2 4-2" to false, "M9 9h.01" to false, "M15 9h.01" to false) }
    val Star: ImageVector by lazy { svgIcon("Star", false, "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z" to false) }
    val Storage: ImageVector by lazy { svgIcon("Storage", false, "M21 5c0 1.66-4.03 3-9 3S3 6.66 3 5s4.03-3 9-3 9 1.34 9 3z" to false, "M21 12c0 1.66-4.03 3-9 3s-9-1.34-9-3" to false, "M3 5v14c0 1.66 4.03 3 9 3s9-1.34 9-3V5" to false) }
    val Sun: ImageVector by lazy { svgIcon("Sun", false, "M7 12a5 5 0 1 0 10 0a5 5 0 1 0 -10 0" to false, "M12 1v2" to false, "M12 21v2" to false, "M4.22 4.22l1.42 1.42" to false, "M18.36 18.36l1.42 1.42" to false, "M1 12h2" to false, "M21 12h2" to false, "M4.22 19.78l1.42-1.42" to false, "M18.36 5.64l1.42-1.42" to false) }
    val TextFields: ImageVector by lazy { svgIcon("TextFields", false, "M4 7V4h16v3" to false, "M9 20h6" to false, "M12 4v16" to false) }
    val Tune: ImageVector by lazy { svgIcon("Tune", false, "M4 21v-7" to false, "M4 10V3" to false, "M12 21v-9" to false, "M12 8V3" to false, "M20 21v-5" to false, "M20 12V3" to false, "M1 14h6" to false, "M9 8h6" to false, "M17 16h6" to false) }
    val Unarchive: ImageVector by lazy { svgIcon("Unarchive", false, "M21 8v13H3V8" to false, "M1 3h22v5H1z" to false, "M12 17v-6" to false, "M9 14l3-3 3 3" to false) }
    val Undo: ImageVector by lazy { svgIcon("Undo", true, "M3 7v6h6" to false, "M21 17a9 9 0 0 0-9-9 9 9 0 0 0-6 2.3L3 13" to false) }
    val Videocam: ImageVector by lazy { svgIcon("Videocam", false, "M23 7l-7 5 7 5V7z" to false, "M3 5h11a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z" to false) }
    val VideocamOff: ImageVector by lazy { svgIcon("VideocamOff", false, "M16 16v1a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h2m5.66 0H14a2 2 0 0 1 2 2v3.34l1 1L23 7v10" to false, "M1 1l22 22" to false) }
    val VolumeOff: ImageVector by lazy { svgIcon("VolumeOff", false, "M11 5L6 9H2v6h4l5 4V5z" to false, "M23 9l-6 6" to false, "M17 9l6 6" to false) }
    val VolumeUp: ImageVector by lazy { svgIcon("VolumeUp", false, "M11 5L6 9H2v6h4l5 4V5z" to false, "M19.07 4.93a10 10 0 0 1 0 14.14" to false, "M15.54 8.46a5 5 0 0 1 0 7.07" to false) }
}
