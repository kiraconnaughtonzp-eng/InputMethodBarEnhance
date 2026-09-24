package com.wetype.enhance.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import com.wetype.enhance.config.Prefs
import com.wetype.enhance.config.Settings
import com.wetype.enhance.log.XLog

/**
 * 快捷按钮的动作实现。
 *
 * 全部走 Android 标准 InputConnection 通道（performContextMenuAction / setSelection / commitText），
 * 不依赖输入法的内部实现，输入法升级后一般不会失效。
 */
object ShortcutActions {

    fun perform(service: InputMethodService, action: BarAction, anchor: View, prefs: Prefs) {
        if (prefs.getBoolean(Settings.Keys.HAPTIC, true)) {
            try {
                anchor.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
                )
            } catch (ignore: Throwable) {
                // 部分机型不支持，忽略
            }
        }
        try {
            dispatch(service, action)
        } catch (t: Throwable) {
            XLog.e("执行动作失败：${action.id}", t)
        }
    }

    private fun dispatch(service: InputMethodService, action: BarAction) {
        when (action) {
            BarAction.SELECT_ALL -> contextMenuAction(service, android.R.id.selectAll, "selectAll")
            BarAction.COPY -> copy(service)
            BarAction.CUT -> contextMenuAction(service, android.R.id.cut, "cut")
            BarAction.PASTE -> paste(service)
            BarAction.CLEAR -> clearAll(service)
            BarAction.CURSOR_LEFT -> moveCursor(service, -1)
            BarAction.CURSOR_RIGHT -> moveCursor(service, 1)
            BarAction.LINE_START -> sendKey(service, KeyEvent.KEYCODE_MOVE_HOME)
            BarAction.LINE_END -> sendKey(service, KeyEvent.KEYCODE_MOVE_END)
            BarAction.UNDO -> contextMenuAction(service, android.R.id.undo, "undo")
            BarAction.REDO -> contextMenuAction(service, android.R.id.redo, "redo")
            BarAction.HIDE_KEYBOARD -> service.requestHideSelf(0)
        }
    }

    private fun contextMenuAction(service: InputMethodService, id: Int, name: String): Boolean {
        val ic = service.currentInputConnection
        if (ic == null) {
            XLog.throttle("ic-null", "当前没有输入连接，$name 未执行", 2000L)
            return false
        }
        val ok = ic.performContextMenuAction(id)
        XLog.v("performContextMenuAction($name) = $ok")
        return ok
    }

    private fun copy(service: InputMethodService) {
        if (contextMenuAction(service, android.R.id.copy, "copy")) return
        val ic = service.currentInputConnection ?: return
        val selected = try {
            ic.getSelectedText(0)?.toString()
        } catch (t: Throwable) {
            null
        }
        if (!selected.isNullOrEmpty()) {
            setClipboardText(service, selected)
            XLog.v("复制走兜底通道：getSelectedText → 系统剪贴板（${selected.length} 字符）")
        } else {
            XLog.i("没有选中内容，复制未执行")
        }
    }

    private fun paste(service: InputMethodService) {
        if (contextMenuAction(service, android.R.id.paste, "paste")) return
        val ic = service.currentInputConnection ?: return
        val text = clipboardText(service)
        if (text.isNullOrEmpty()) {
            XLog.i("系统剪贴板为空，粘贴未执行")
            return
        }
        ic.commitText(text, 1)
        XLog.v("粘贴走兜底通道：commitText（${text.length} 字符）")
    }

    private fun clearAll(service: InputMethodService) {
        val ic = service.currentInputConnection ?: return
        val selectOk = try {
            ic.performContextMenuAction(android.R.id.selectAll)
        } catch (t: Throwable) {
            false
        }
        if (!selectOk) {
            XLog.i("清空失败：无法全选（当前输入框可能不支持）")
            return
        }
        val selected = try {
            ic.getSelectedText(0)?.toString()
        } catch (t: Throwable) {
            null
        }
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            XLog.i("已清空输入框内容（${selected.length} 字符）")
        } else {
            // 有些输入框不返回选中文本，退化成"全选 + 删除键"
            sendKey(ic, KeyEvent.KEYCODE_DEL)
            XLog.i("已清空输入框内容（全选 + 删除键）")
        }
    }

    private fun moveCursor(service: InputMethodService, delta: Int) {
        val ic = service.currentInputConnection ?: return
        val request = ExtractedTextRequest().apply {
            hintMaxLines = 1
            hintMaxChars = 0
        }
        val extracted = try {
            ic.getExtractedText(request, 0)
        } catch (t: Throwable) {
            null
        }
        val start = extracted?.selectionStart ?: -1
        if (extracted == null || start < 0) {
            sendKey(ic, if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
            return
        }
        val length = extracted.text?.length ?: 0
        val target = (start + delta).coerceIn(0, length)
        val ok = try {
            ic.setSelection(target, target)
        } catch (t: Throwable) {
            false
        }
        if (!ok) {
            sendKey(ic, if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
        } else {
            XLog.v("光标移动：$start → $target（正文长度 $length）")
        }
    }

    private fun sendKey(service: InputMethodService, keyCode: Int) {
        val ic = service.currentInputConnection ?: return
        sendKey(ic, keyCode)
    }

    private fun sendKey(ic: InputConnection, keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    // ------------------------------------------------------------------ 系统剪贴板（仅作为复制/粘贴的兜底）

    private fun clipboardText(ctx: Context): String? = try {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        if (clip == null || clip.itemCount == 0) {
            null
        } else {
            clip.getItemAt(0).coerceToText(ctx)?.toString()?.takeIf { it.isNotEmpty() }
        }
    } catch (t: Throwable) {
        XLog.throttle("clip-read-fail", "读取系统剪贴板失败：${t.javaClass.simpleName}", 10000L)
        null
    }

    private fun setClipboardText(ctx: Context, text: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("ime-enhance", text))
        } catch (t: Throwable) {
            XLog.e("写入系统剪贴板失败", t)
        }
    }
}
