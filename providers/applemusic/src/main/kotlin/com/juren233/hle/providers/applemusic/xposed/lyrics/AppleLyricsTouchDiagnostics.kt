/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import android.app.Activity
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/**
 * 仅 debug 诊断：歌词页「滑不动」时记录列表自身的可滚动状态、按下点命中的视图链、
 * 盖在列表上方的可点击视图，以及列表是否收到触摸事件与是否进入拖动。
 *
 * 调用方必须在 [AppleProviderEnvironment.diagnosticsEnabled] 为真时才安装 Hook；
 * 本类不做任何会改变宿主行为的操作，只读视图树并写日志。
 */
internal class AppleLyricsTouchDiagnostics(
    private val isLyricsRecycler: (Any) -> Boolean,
) {
    private var gestureDownTime = -1L
    private var moveLogged = false
    private var lastSnapshotUptime = 0L

    /** 列表 `onInterceptTouchEvent` / `onTouchEvent` 之后调用：确认列表是否收到事件及处理结果。 */
    fun onRecyclerEvent(stage: String, recycler: Any?, event: Any?, result: Any?) {
        val view = recycler as? View ?: return
        val motion = event as? MotionEvent ?: return
        if (!isLyricsRecycler(view)) return
        val action = motion.actionMasked
        val newGesture = action == MotionEvent.ACTION_DOWN || motion.downTime != gestureDownTime
        if (newGesture) {
            gestureDownTime = motion.downTime
            moveLogged = false
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (moveLogged) return
            moveLogged = true
        }
        if (action != MotionEvent.ACTION_DOWN &&
            action != MotionEvent.ACTION_MOVE &&
            action != MotionEvent.ACTION_UP &&
            action != MotionEvent.ACTION_CANCEL
        ) return
        log(
            "recycler_event stage=$stage action=${MotionEvent.actionToString(action)}, " +
                "result=$result, scrollState=${scrollState(view)}, " +
                "canScrollDown=${view.canScrollVertically(1)}, canScrollUp=${view.canScrollVertically(-1)}"
        )
    }

    /** `Activity.dispatchTouchEvent` 之后调用：按下时抓一次列表状态与命中链。 */
    fun onActivityTouch(activity: Any?, event: Any?) {
        val host = activity as? Activity ?: return
        val motion = event as? MotionEvent ?: return
        if (motion.actionMasked != MotionEvent.ACTION_DOWN) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSnapshotUptime < SNAPSHOT_MIN_INTERVAL_MS) return
        val decor = host.window?.decorView ?: return
        val recycler = findLyricsRecycler(decor, 0) ?: return
        lastSnapshotUptime = now
        val x = motion.rawX.toInt()
        val y = motion.rawY.toInt()
        val bounds = screenBounds(recycler)
        log(
            "down activity=${host.javaClass.simpleName}, point=($x,$y), " +
                "recyclerBounds=${bounds.toShortString()}, inside=${bounds.contains(x, y)}, " +
                "windowFocus=${decor.hasWindowFocus()}"
        )
        log("recycler_state ${describeRecycler(recycler)}")
        log("hit_chain ${hitChain(decor, x, y)}")
        log("overlays ${overlaysAbove(decor, recycler, bounds)}")
    }

    private fun describeRecycler(recycler: View): String {
        val layoutManager = call(recycler, "getLayoutManager")
        val adapter = call(recycler, "getAdapter")
        val parents = buildList {
            var parent = recycler.parent
            while (parent is View && size < 6) {
                add(describe(parent))
                parent = parent.parent
            }
        }
        return "recycler=${describe(recycler)}, shown=${recycler.isShown}, " +
            "scrollState=${scrollState(recycler)}, " +
            "canScrollDown=${recycler.canScrollVertically(1)}, canScrollUp=${recycler.canScrollVertically(-1)}, " +
            "layoutSuppressed=${call(recycler, "isLayoutSuppressed") ?: call(recycler, "isLayoutFrozen")}, " +
            "nestedScrolling=${recycler.isNestedScrollingEnabled}, " +
            "layoutManager=${layoutManager?.javaClass?.name}, " +
            "lmCanScrollVertically=${layoutManager?.let { call(it, "canScrollVertically") }}, " +
            "itemCount=${adapter?.let { call(it, "getItemCount") }}, " +
            "childCount=${(recycler as? ViewGroup)?.childCount}, parents=$parents"
    }

    private fun hitChain(root: View, x: Int, y: Int): String {
        val chain = mutableListOf<String>()
        var current: View? = root
        while (current != null && chain.size < MAX_CHAIN) {
            chain += describe(current)
            val group = current as? ViewGroup ?: break
            var next: View? = null
            for (index in group.childCount - 1 downTo 0) {
                val child = group.getChildAt(index)
                if (child.visibility == View.VISIBLE && screenBounds(child).contains(x, y)) {
                    next = child
                    break
                }
            }
            current = next
        }
        return chain.joinToString(" > ")
    }

    private fun overlaysAbove(root: View, recycler: View, bounds: Rect): String {
        val found = mutableListOf<String>()
        var passedRecycler = false
        fun visit(view: View) {
            if (found.size >= MAX_OVERLAYS) return
            if (view === recycler) {
                passedRecycler = true
                return
            }
            if (passedRecycler &&
                view.visibility == View.VISIBLE &&
                view.alpha > 0.01f &&
                (view.isClickable || view.isLongClickable) &&
                Rect.intersects(bounds, screenBounds(view))
            ) {
                found += describe(view) + "@" + screenBounds(view).toShortString()
            }
            (view as? ViewGroup)?.let { group ->
                for (index in 0 until group.childCount) visit(group.getChildAt(index))
            }
        }
        visit(root)
        return if (found.isEmpty()) "none" else found.joinToString("; ")
    }

    private fun findLyricsRecycler(view: View, depth: Int): View? {
        if (depth > MAX_DEPTH || view.visibility != View.VISIBLE) return null
        if (isRecyclerView(view) && isLyricsRecycler(view)) return view
        val group = view as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findLyricsRecycler(group.getChildAt(index), depth + 1)?.let { return it }
        }
        return null
    }

    private fun screenBounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun describe(view: Any): String {
        val target = view as? View ?: return view.javaClass.simpleName
        val name = runCatching {
            if (target.id == View.NO_ID) null else target.resources.getResourceEntryName(target.id)
        }.getOrNull()
        return "${target.javaClass.simpleName}#${name ?: "-"}" +
            "[vis=${target.visibility},en=${target.isEnabled},clk=${target.isClickable}," +
            "a=${"%.2f".format(target.alpha)}]"
    }

    private fun scrollState(recycler: View): Any? = call(recycler, "getScrollState")

    private fun isRecyclerView(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == RECYCLER_CLASS) return true
            type = type.superclass
        }
        return false
    }

    private fun call(target: Any, name: String): Any? = runCatching {
        target.javaClass.getMethod(name).apply { isAccessible = true }.invoke(target)
    }.getOrNull()

    private fun log(message: String) {
        ProviderLogger.debug("[LyricsTouchDiag] $message")
    }

    private companion object {
        const val RECYCLER_CLASS = "androidx.recyclerview.widget.RecyclerView"
        const val SNAPSHOT_MIN_INTERVAL_MS = 400L
        const val MAX_DEPTH = 30
        const val MAX_CHAIN = 14
        const val MAX_OVERLAYS = 6
    }
}
