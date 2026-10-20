package dev.larryjones.quietboard

import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityNodeProvider

/**
 * Makes things a single View draws itself (keys, suggestion slots) visible to TalkBack as separate
 * items: a minimal stand-in for AndroidX's ExploreByTouchHelper, since the app has no dependencies.
 *
 * Touch exploration moves accessibility focus to the item under the finger; TalkBack's double-tap
 * (or its lift-to-type setting) sends a click to the focused item.
 *
 * The host must return this from getAccessibilityNodeProvider() and route dispatchHoverEvent through
 * [onHover]. Subclasses work with their own small "local" ids; the ids handed to Android carry a
 * generation that changes with every [invalidateAll], so after a layout switch a stale id (TalkBack
 * still focused on "?123") can't land on whatever key now has the same position.
 */
abstract class VirtualViews(private val host: View) : AccessibilityNodeProvider() {
    /** Ids of the items currently on screen. */
    /** Local ids of the items currently on screen (0 until [GEN_STRIDE]). */
    abstract fun ids(): IntArray
    /** The item at a point in host coordinates, or [INVALID]. */
    abstract fun idAt(x: Float, y: Float): Int
    /** Writes the item's bounds in host coordinates; false if the id is gone. */
    abstract fun bounds(id: Int, out: Rect): Boolean
    abstract fun describe(id: Int): CharSequence?
    open fun canLongClick(id: Int) = false
    /** Extra actions TalkBack offers in its actions menu, as (action id, label). */
    open fun customActions(id: Int): List<Pair<Int, CharSequence>> = emptyList()
    open fun performCustom(id: Int, action: Int): Boolean = false
    /** Activates the item, as a tap (or long press) would. */
    abstract fun click(id: Int, long: Boolean): Boolean

    private val am = host.context.getSystemService(AccessibilityManager::class.java)
    private var hovered = INVALID
    private var focused = INVALID
    private var generation = 0

    private fun toVirtual(local: Int) = generation * GEN_STRIDE + local

    /** The id Android currently sees for a local id (tests). */
    internal fun virtualId(local: Int) = toVirtual(local)

    /** The local id for a virtual id from this generation, or [INVALID]. */
    private fun toLocal(virtual: Int): Int {
        if (virtual < 0 || virtual / GEN_STRIDE != generation) return INVALID
        val local = virtual % GEN_STRIDE
        return if (local in ids()) local else INVALID
    }
    private val rect = Rect()
    private val loc = IntArray(2)

    fun onHover(event: MotionEvent): Boolean {
        if (am == null || !am.isEnabled || !am.isTouchExplorationEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE ->
                setHovered(idAt(event.x, event.y).let { if (it == INVALID) INVALID else toVirtual(it) })
            MotionEvent.ACTION_HOVER_EXIT -> setHovered(INVALID)
        }
        return true
    }

    /** Call when the set of items changes (new layout, suggestions replaced by the toolbar). */
    fun invalidateAll() {
        // Tell services the focused item is gone before its id stops existing.
        if (focused != INVALID) send(focused, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
        hovered = INVALID
        focused = INVALID
        generation = (generation + 1) % MAX_GENERATIONS
        if (am?.isEnabled != true) return
        val parent = host.parent ?: return
        @Suppress("DEPRECATION")
        val ev = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ev.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        host.onInitializeAccessibilityEvent(ev)
        parent.requestSendAccessibilityEvent(host, ev)
    }

    private fun setHovered(id: Int) {
        if (id == hovered) return
        val prev = hovered
        hovered = id
        if (id != INVALID) send(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
        if (prev != INVALID) send(prev, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
    }

    @Suppress("DEPRECATION") // obtain(): the constructors need API 30, minSdk is 29
    private fun send(virtual: Int, type: Int, description: CharSequence? = null) {
        if (am?.isEnabled != true) return
        val parent = host.parent ?: return
        val ev = AccessibilityEvent.obtain(type)
        ev.packageName = host.context.packageName
        ev.className = ITEM_CLASS
        ev.contentDescription = description ?: toLocal(virtual).let { if (it == INVALID) null else describe(it) }
        ev.isEnabled = true
        ev.setSource(host, virtual)
        parent.requestSendAccessibilityEvent(host, ev)
    }

    @Suppress("DEPRECATION")
    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        if (virtualViewId == HOST_VIEW_ID) {
            val info = AccessibilityNodeInfo.obtain(host)
            host.onInitializeAccessibilityNodeInfo(info)
            for (id in ids()) info.addChild(host, toVirtual(id))
            return info
        }
        val local = toLocal(virtualViewId)
        if (local == INVALID || !bounds(local, rect)) return null
        val info = AccessibilityNodeInfo.obtain(host, virtualViewId)
        info.packageName = host.context.packageName
        info.className = ITEM_CLASS
        info.contentDescription = describe(local)
        info.setParent(host)
        info.setBoundsInParent(rect)
        host.getLocationOnScreen(loc)
        rect.offset(loc[0], loc[1])
        info.setBoundsInScreen(rect)
        info.isEnabled = true
        info.isVisibleToUser = true
        info.isClickable = true
        info.addAction(AccessibilityAction.ACTION_CLICK)
        for ((action, label) in customActions(local)) info.addAction(AccessibilityAction(action, label))
        if (canLongClick(local)) {
            info.isLongClickable = true
            info.addAction(AccessibilityAction.ACTION_LONG_CLICK)
        }
        val isFocused = focused == virtualViewId
        info.isAccessibilityFocused = isFocused
        info.addAction(
            if (isFocused) AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
            else AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS
        )
        return info
    }

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (virtualViewId == HOST_VIEW_ID) return host.performAccessibilityAction(action, arguments)
        val local = toLocal(virtualViewId)
        if (local == INVALID) return false
        return when (action) {
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                if (focused == virtualViewId) return false
                focused = virtualViewId
                host.invalidate()
                send(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                true
            }
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (focused != virtualViewId) return false
                focused = INVALID
                host.invalidate()
                send(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                true
            }
            AccessibilityNodeInfo.ACTION_CLICK -> {
                // Describe first: the click may change what's on screen (a layout switch).
                val what = describe(local)
                click(local, false).also { if (it) send(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED, what) }
            }
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> canLongClick(local) && click(local, true)
            else -> performCustom(local, action)
        }
    }

    companion object {
        const val INVALID = Int.MIN_VALUE
        const val GEN_STRIDE = 100_000
        private const val MAX_GENERATIONS = 20_000
        private const val ITEM_CLASS = "android.widget.Button"
    }
}
