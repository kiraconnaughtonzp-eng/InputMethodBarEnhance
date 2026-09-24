package com.wetype.enhance.config

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 模块 App 侧的命令发送器（框架自带）。
 *
 * 原理：把命令写进配置（带一个自增的请求号）→ 目标进程轮询到变化后执行 →
 * 结果写回诊断表 → 这里轮询诊断表拿到结果回调。
 */
object CommandChannel {

    private const val POLL_INTERVAL_MS = 300L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * 发送命令。[callback] 在主线程回调：拿到结果文本，超时/通道不可用则回 null。
     */
    fun send(
        ctx: Context,
        name: String,
        arg: String,
        timeoutMs: Long = 8000L,
        callback: (String?) -> Unit,
    ) {
        val requestId = System.currentTimeMillis()
        val (config, _) = LocalConfig.load(ctx)
        config.set(ConfigContract.CFG_CMD_REQUEST_ID, requestId)
            .set(ConfigContract.CFG_CMD_NAME, name)
            .set(ConfigContract.CFG_CMD_ARG, arg)
        LocalConfig.save(ctx, config)
        poll(ctx, requestId, System.currentTimeMillis() + timeoutMs, callback)
    }

    private fun poll(ctx: Context, requestId: Long, deadline: Long, callback: (String?) -> Unit) {
        val diag = LocalConfig.readDiag(ctx)
        if (diag.cmdId == requestId) {
            callback(diag.cmdResult)
            return
        }
        if (System.currentTimeMillis() > deadline) {
            callback(null)
            return
        }
        mainHandler.postDelayed({ poll(ctx, requestId, deadline, callback) }, POLL_INTERVAL_MS)
    }
}
