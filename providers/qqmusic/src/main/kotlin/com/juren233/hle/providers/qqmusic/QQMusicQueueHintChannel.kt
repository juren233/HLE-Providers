/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.qqmusic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.json.JSONObject

/**
 * QQ 音乐手机版的播放队列快照（QQ 自己给每首歌关联的在线 ID）在主进程采集，歌词运行在
 * `:QQPlayerService`。两个进程同属 QQ 的 uid，用限定本包的应用内广播转发快照：
 * 发送端 `setPackage` 只投给 QQ 自己，接收端以 `RECEIVER_NOT_EXPORTED` 注册，只收同 uid 的广播。
 * 快照内容不可信（可能是本地库编号），采用前由换算器经详情接口核对。
 */
internal object QQMusicQueueHintChannel {
    const val ACTION = "com.juren233.hle.providers.qqmusic.QUEUE_HINT"
    private const val EXTRA_SNAPSHOT = "snapshot"

    fun send(context: Context, snapshot: QQMusicQueueSnapshot) {
        context.sendBroadcast(
            Intent(ACTION)
                .setPackage(context.packageName)
                .putExtra(EXTRA_SNAPSHOT, encode(snapshot)),
        )
    }

    fun register(context: Context, onSnapshot: (QQMusicQueueSnapshot) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION) return
                decode(intent.getStringExtra(EXTRA_SNAPSHOT))?.let(onSnapshot)
            }
        }
        context.registerReceiver(receiver, IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED)
        return receiver
    }

    fun encode(snapshot: QQMusicQueueSnapshot): String = JSONObject()
        .put("current", snapshot.current.toJson())
        .apply { snapshot.next?.let { put("next", it.toJson()) } }
        .toString()

    fun decode(raw: String?): QQMusicQueueSnapshot? = runCatching {
        val root = JSONObject(raw ?: return null)
        val current = root.optJSONObject("current")?.toTrack() ?: return null
        QQMusicQueueSnapshot(current, root.optJSONObject("next")?.toTrack())
    }.getOrNull()

    private fun QQMusicTrackSnapshot.toJson() = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .putOpt("songMid", songMid)
        .put("duration", duration)

    private fun JSONObject.toTrack(): QQMusicTrackSnapshot? {
        val id = optString("id").takeIf(String::isNotBlank) ?: return null
        return QQMusicTrackSnapshot(
            id = id,
            title = optString("title"),
            artist = optString("artist"),
            songMid = optString("songMid").takeIf(String::isNotBlank),
            duration = optLong("duration", 0L),
        )
    }
}
