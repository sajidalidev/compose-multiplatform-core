/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.scene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.TvOSHapticFeedback
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.PointerKeyboardModifiers
import androidx.compose.ui.input.key.copy
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.toComposeEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.HistoricalChange
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.remote.RemoteFastScrollExitAnchor
import androidx.compose.ui.input.remote.RemoteFastScrollNode
import androidx.compose.ui.input.remote.RemoteSwipe
import androidx.compose.ui.input.remote.RemoteSwipeDirection
import androidx.compose.ui.input.remote.newRemoteSwipe
import androidx.compose.ui.navigationevent.TvBackNavigationEventInput
import androidx.compose.ui.platform.AccessibilityMediator
import androidx.compose.ui.platform.CUPERTINO_TOUCH_SLOP
import androidx.compose.ui.platform.DefaultInputModeManager
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformArchitectureComponentsOwner
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformScreenReader
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformWindowContext
import androidx.compose.ui.platform.TvOSTextInputService
import androidx.compose.ui.platform.UIKitIdleTimerManager
import androidx.compose.ui.platform.UIKitWindowInsetsManager
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.uikit.InterfaceOrientation
import androidx.compose.ui.uikit.LocalUIView
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.uikit.density
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.unit.roundToIntSize
import androidx.compose.ui.unit.toDpOffset
import androidx.compose.ui.unit.toDpRect
import androidx.compose.ui.unit.toDpSize
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.LocalInteropContainer
import androidx.compose.ui.viewinterop.TrackInteropPlacementContainer
import androidx.compose.ui.viewinterop.UIKitInteropContainer
import androidx.compose.ui.viewinterop.UIKitInteropTransaction
import androidx.compose.ui.window.FocusedViewsList
import androidx.compose.ui.window.MetalRedrawer
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.cinterop.CValue
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRectIsEmpty
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSProcessInfo
import platform.QuartzCore.CACurrentMediaTime
import platform.UIKit.UIEvent
import platform.UIKit.UIEventButtonMaskPrimary
import platform.UIKit.UIEventButtonMaskSecondary
import platform.UIKit.UIPress
import platform.UIKit.UIPressPhase
import platform.UIKit.UIPressesEvent
import platform.UIKit.UITouch
import platform.UIKit.UITouchPhase
import platform.UIKit.UITouchTypeDirect
import platform.UIKit.UITouchTypeIndirect
import platform.UIKit.UITouchTypeIndirectPointer
import platform.UIKit.UIView
import platform.UIKit.endEditing
import platform.UIKit.setAccessibilityElements

// Siri Remote swipe recognition tuning. Values measured on a second-generation Siri Remote
// (GameController micro gamepad, reportsAbsoluteDpadValues = true) with the display-link
// sampling of SiriRemoteTouchOracle, which catches the origin of a contact within a frame of the
// finger landing: centre swipes start at radius <= 0.33, ring drags at >= 0.94; genuine swipes
// cross 0.30 within 60 to 151 ms; a resting finger, a ring rest and the drift inside a ring click
// travel at most 0.25 and need 179 to 406 ms to get there.
//
// Where a contact started decides how it is judged, and the radii of that are in TvRingGate:
// a contact that lands on the centre pad is a swipe candidate as it is, while one that lands on
// the outer ring of arrow buttons only becomes one once it crossed into the centre pad. That is
// what a straight swipe from one edge of the ring to the opposite edge does, and what a circular
// drag around the ring never does. A swipe that passes the gates below moves focus once, wherever
// it started, then keeps focus moving for as many rows as its travel and its speed earn, and
// item by item while the finger glides on; that momentum is in TvSwipeMomentum.
// A contact that crosses the distance gate later than this is a drag, not a swipe: it still moves
// focus, and keeps moving it as the finger glides on (DRAG_STEP_TRAVEL), but it earns no momentum,
// the way a slow finger moves the native focus engine item by item without a flick. Genuine swipes
// cross the gate within 151 ms, while the contact roll of a centre click that lands partly on the
// ring takes 300 ms or more to cover the same distance, so a slow contact only moves focus once it
// travelled the full gate and no press claimed it.
private const val SWIPE_MAX_DURATION_S = 0.25
// Minimum travel along the dominant axis, in absolute clickpad units.
private const val SWIPE_DISTANCE_NORMALIZED = 0.30f
// A finger that stayed within this radius of its rest anchor for REST_RESET_DURATION_S is at
// rest: the origin and the timer move to where it rests, so a rest-then-move still swipes and a
// slow drift never accumulates into one.
private const val REST_ANCHOR_TOLERANCE = 0.02f
private const val REST_RESET_DURATION_S = 0.15
// Minimum travel along the dominant axis, in dp, when no controller is reported and the relative
// UIKit location is the only signal.
private const val SWIPE_DISTANCE_FALLBACK_DP = 40f
// The dominant axis must travel at least this many times the other one, so a diagonal smear does
// not move focus in an arbitrary direction.
private const val SWIPE_AXIS_DOMINANCE = 2f
// A swipe that crossed the distance gate waits this long before it is dispatched, so a Select
// press that arrives right after the contact rolled far enough cancels it instead of firing both.
// A contact that ends while armed is dispatched at once: a lifted finger can no longer click. The
// native engine has no such wait, so the hold is kept short; no click got through during device
// tests at this value, but the press race was not timed. Provisional.
private const val DISPATCH_HOLD_S = 0.05
// A contact live for longer than this lost its terminal event: tvOS can absorb a touch's
// terminal event, e.g. when the keyboard overlay appears; without this the display link would
// poll forever.
private const val INDIRECT_CONTACT_MAX_AGE_S = 2.0
// A finger that pans fast scrolling can stay down far longer than a swipe, so a contact that lost
// its terminal event is only given up on after this long. An empty pad ends it sooner.
private const val FAST_SCROLL_CONTACT_MAX_AGE_S = 20.0
/** Interval between two fast scroll steps beyond which the slow step is logged, in seconds. */
private const val FAST_SLOW_STEP_S = 0.025
/** Least time between two lines of the same rate limited fast scroll debug log, in seconds. */
private const val FAST_LOG_INTERVAL_S = 0.1
// A clickpad press this long before a contact starts belongs to that contact: finger contact
// physically precedes the switch closing, and the two sensors stamp their events a few ms apart.
private const val PRESS_SUPPRESSION_WINDOW_S = 0.15

private val isSwipeDebugEnabled: Boolean by lazy {
    NSProcessInfo.processInfo.environment["COMPOSE_TVOS_SWIPE_DEBUG"] == "1"
}

private inline fun swipeDebug(message: () -> String) {
    if (isSwipeDebugEnabled) {
        println(message())
    }
}

/** [seconds] with three decimals, the form the swipe debug lines stamp their time in. */
private fun debugSeconds(seconds: Double): String {
    val millis = kotlin.math.round(seconds * 1000.0).toLong()
    val sign = if (millis < 0) "-" else ""
    val abs = kotlin.math.abs(millis)
    return "$sign${abs / 1000}.${(abs % 1000).toString().padStart(3, '0')}"
}

/** The direction a swipe that dispatched [key] travelled in. */
private fun remoteSwipeDirection(key: Key): RemoteSwipeDirection =
    when (key) {
        Key.DirectionRight -> RemoteSwipeDirection.Right
        Key.DirectionLeft -> RemoteSwipeDirection.Left
        Key.DirectionDown -> RemoteSwipeDirection.Down
        else -> RemoteSwipeDirection.Up
    }

private fun directionName(key: Key): String =
    when (key) {
        Key.DirectionRight -> "DirectionRight"
        Key.DirectionLeft -> "DirectionLeft"
        Key.DirectionDown -> "DirectionDown"
        else -> "DirectionUp"
    }

/**
 * The state of one Siri Remote indirect contact.
 *
 * A contact produces at most one directional key: it starts as a [CANDIDATE] (or as a
 * [RING_CANDIDATE] when it started on the ring), becomes [ARMED] with a direction once it travelled
 * far enough, and leaves that state for good once the swipe is [DISPATCHED] or a clickpad press or
 * an overlong contact makes it [CANCELLED].
 *
 * A contact whose BEGAN arrives before the oracle reported any position of that contact starts as
 * [PENDING]: the pad reads exactly (0, 0) between contacts, so there is nothing to compare the
 * origin against yet. The first later sample decides the origin and the verdict.
 */
private enum class IndirectTouchVerdict {
    PENDING,
    CANDIDATE,
    RING_CANDIDATE,
    ARMED,
    CANCELLED,
    DISPATCHED,
}

private class IndirectTouchState(
    var origin: Offset,
    var startTimestamp: Double,
    val beginTimestamp: Double,
    val usesOracle: Boolean,
    var verdict: IndirectTouchVerdict,
) {
    /** What the key events of this contact's swipe carry, momentum included. */
    val swipe: RemoteSwipe = newRemoteSwipe()

    /** Position the rest detection measures against, and when it was last left. */
    var restAnchor: Offset = origin
    var lastSignificantMoveTime: Double = startTimestamp

    /** Whether the contact started on the ring, from the origin it is anchored at now. */
    var ringOrigin: Boolean = false

    /** The smallest radius the contact was seen at since its origin was last anchored. */
    var minRadius: Float = hypot(origin.x, origin.y)

    /** The direction the contact armed, and when it armed it. */
    var armedKey: Key? = null
    var armedAt: Double = 0.0

    /** The direction the contact dispatched, which is the direction its momentum continues in. */
    var dispatchedKey: Key? = null

    /**
     * The furthest the contact travelled along the axis of its armed key, in pad units, which is
     * what its momentum is sized from, with the sample and the time it was reached at: a finger
     * that comes back from there starts over at that point.
     */
    var maxTravel: Float = 0f
    var maxTravelSample: Offset? = null
    var maxTravelTime: Double = 0.0

    /**
     * The highest speed the finger reached along the axis of its armed key, toward that key, in pad
     * units per second, since it armed: what its momentum is sized from besides its travel.
     */
    var peakSpeed: Float = 0f

    /** Whether the contact's swipe continues its chain right after a momentum swipe. */
    var followsMomentumSwipe: Boolean = false

    /**
     * Whether the contact lasted at most [CHAIN_QUICK_CONTACT_S] from its BEGAN to its last sample,
     * known once it lifted.
     */
    var quickContact: Boolean = false

    /**
     * Set when the contact crossed the distance gate slower than [SWIPE_MAX_DURATION_S]: its moves
     * follow the finger and it earns no momentum at lift.
     */
    var dragOnly: Boolean = false

    /**
     * The moves the travel of a dispatched contact has paid for, its own move included, and the
     * travel at which the next one is due. A move the momentum curve has not emitted by the time
     * the finger paid for it goes out at once.
     */
    var travelSteps: Int = 0
    var dragNextTravel: Float = 0f

    /** Set once the lift of the contact was handled, which happens at most once. */
    var liftHandled: Boolean = false

    /** Set once the lift of the contact was logged and evaluated for a lift-time arm. */
    var liftSeen: Boolean = false

    /** Consecutive empty-pad ticks seen for the contact, reset by any sample. */
    var emptyPadTicks: Int = 0

    /** Set when the swipe was armed at lift: it moves one row and earns no momentum. */
    var liftArmed: Boolean = false

    /** When [lastSample] was recorded, and how many samples the contact recorded in all. */
    var lastSampleTime: Double = beginTimestamp
    var sampleCount: Int = 0

    /**
     * The last time the contact moved by more than the rest tolerance between two samples,
     * initialised to its BEGAN time. The stale purge ages a contact from here, so a finger that
     * rests and then swipes is not dropped.
     */
    var lastSampleMoveTime: Double = beginTimestamp

    /** Trailing positions of the contact, for the speed it is reported with and its reversal. */
    val flingSamples = FlingSampleBuffer()

    /**
     * The last position recorded for the contact. The pad snaps to (0, 0) at lift rather than
     * reporting the finger all the way out, so this is how far the swipe is known to have gone.
     */
    var lastSample: Offset? = null

    /**
     * Set once this contact can no longer be the finger the pad reports: the pad went empty under
     * it, or another contact began. What it recorded before that still earns momentum; the samples
     * after it belong to somebody else.
     */
    var flingTrackingClosed = false

    /**
     * Set when the contact began while fast scrolling ran: it pans and flicks the scroll instead of
     * swiping, so the swipe recognition never looks at it. [fastScrollLifted] is set once the
     * physics was told that the finger left.
     */
    var fastScrollContact: Boolean = false
    var fastScrollLifted: Boolean = false
}

/**
 * One run of the fast scrolling mode of a container: the node it drives, the physics, and what the
 * exit needs. [holdKeyId] is the held key that drives it, if any.
 */
private class FastScrollSession(
    val node: RemoteFastScrollNode,
    val physics: TvFastScrollPhysics,
    val vertical: Boolean,
    var holdKeyId: Long?,
    val samplingToken: Int,
    val hadController: Boolean,
    val startedAt: Double,
) {
    val exitRetries = TvRetryBudget(FAST_EXIT_FOCUS_RETRY_FRAMES)

    /** The sign of the last scroll requested, which says which end the content ended at. */
    var lastRequested: Float = 0f

    /** Set once the physics asked to exit and focus is being restored, so nothing scrolls any more. */
    var exitReason: String? = null
    var exitAnchor: RemoteFastScrollExitAnchor = RemoteFastScrollExitAnchor.Center
    var controllerLost: Boolean = false
    var edgeWaiting: Boolean = false
    var lastStepLogAt: Double = startedAt
    var lastShortLogAt: Double = Double.NEGATIVE_INFINITY
    var lastSlowLogAt: Double = Double.NEGATIVE_INFINITY
}

/**
 * A reason for why touches are sent to Compose. Mirrors the iOS [TouchesEventKind] which is not
 * available in tvosMain.
 */
internal enum class TouchesEventKind {
    BEGAN,
    MOVED,
    ENDED,
}

/**
 * tvOS specific-implementation of [PlatformContext.SemanticsOwnerListener] used to track changes in
 * [SemanticsOwner].
 *
 * @property view The UI container associated with the semantics owner.
 * @property coroutineContext The coroutine context to use for handling semantics changes.
 * @property performEscape A lambda to delegate accessibility escape operation. Returns true if the
 *   escape was handled, false otherwise.
 */
private class SemanticsOwnerListenerImpl(
    private val view: UIView,
    private val coroutineContext: CoroutineContext,
    private val performEscape: () -> Boolean,
    private val onScreenReaderActive: (Boolean) -> Unit,
) : PlatformContext.SemanticsOwnerListener {

    private var accessibilityMediator: AccessibilityMediator? = null

    var isEnabled: Boolean = false
        set(value) {
            field = value
            accessibilityMediator?.isEnabled = value
        }

    override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
        if (accessibilityMediator == null) {
            accessibilityMediator =
                AccessibilityMediator(
                        view,
                        semanticsOwner,
                        coroutineContext,
                        performEscape,
                        onScreenReaderActive,
                    )
                    .also { it.isEnabled = isEnabled }
        }
    }

    override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
        if (accessibilityMediator?.owner == semanticsOwner) {
            accessibilityMediator?.dispose()
            accessibilityMediator = null
            onScreenReaderActive(false)
        }
    }

    override fun onSemanticsChange(semanticsOwner: SemanticsOwner) {
        if (accessibilityMediator?.owner == semanticsOwner) {
            accessibilityMediator?.onSemanticsChange()
        }
    }

    override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) {
        if (accessibilityMediator?.owner == semanticsOwner) {
            accessibilityMediator?.onLayoutChange(nodeId = semanticsNodeId)
        }
    }

    val hasInvalidations: Boolean
        get() = accessibilityMediator?.hasPendingInvalidations ?: false

    fun dispose() {
        accessibilityMediator?.dispose()
        accessibilityMediator = null
    }
}

/**
 * A simple overlay view for tvOS that handles touch events from the Siri Remote touch surface and
 * keyboard presses. Unlike the iOS [OverlayInputView], this does not handle hover gestures, scroll
 * gestures, or native text input, as those are not applicable on tvOS.
 */
private class TvOverlayInputView(
    private var hitTestInteropView: (point: CValue<CGPoint>) -> UIView?,
    private var isPointInsideInteractionBounds: (CValue<CGPoint>) -> Boolean,
    private var onTouchesEvent:
        (touches: Set<*>, event: UIEvent?, phase: TouchesEventKind) -> PointerEventResult,
    private var onCancelAllTouches: (touches: Set<*>) -> Unit,
    private var onKeyboardPresses: (presses: Set<*>, event: UIPressesEvent?) -> TvPressForwarding,
) : UIView(CGRectZero.readValue()) {

    var isInterceptingOutsideEvents: Boolean = false
    var onOutsidePointerEvent: (PointerEventType) -> Unit = {}

    private var onAppeared: (() -> Unit)? = null

    init {
        // multipleTouchEnabled not available on tvOS K/N bindings
    }

    override fun canBecomeFirstResponder() = true

    override fun canBecomeFocused(): Boolean = false

    override fun pressesBegan(presses: Set<*>, withEvent: UIPressesEvent?) {
        // Compose gets every press in both phases, and the real Began of a Menu press it leaves
        // unhandled is forwarded from here. UIKit arms its Home gesture only on a Began delivered
        // while the press is live, so this phase cannot wait for the KeyUp; a KeyUp Compose
        // claims cancels the forwarded press instead.
        val forwarding = onKeyboardPresses(presses, withEvent)
        if (forwarding.passThrough.isNotEmpty()) {
            super.pressesBegan(forwarding.passThrough, withEvent)
        }
        forwarding.onForwardingFinished()
    }

    override fun pressesEnded(presses: Set<*>, withEvent: UIPressesEvent?) {
        // The Ended of a press Compose wanted in neither phase completes the sequence whose Began
        // was forwarded. When Compose did claim this KeyUp, the press is cancelled from inside
        // this live call instead, so UIKit drops the Home gesture its Began armed.
        val forwarding = onKeyboardPresses(presses, withEvent)
        if (forwarding.passThrough.isNotEmpty()) {
            if (forwarding.mode == TvPressForwardingMode.Cancelled) {
                super.pressesCancelled(forwarding.passThrough, withEvent)
            } else {
                super.pressesEnded(forwarding.passThrough, withEvent)
            }
        }
        forwarding.onForwardingFinished()
    }

    override fun pressesCancelled(presses: Set<*>, withEvent: UIPressesEvent?) {
        val forwarding = onKeyboardPresses(presses, withEvent)
        if (forwarding.passThrough.isNotEmpty()) {
            super.pressesCancelled(forwarding.passThrough, withEvent)
        }
        forwarding.onForwardingFinished()
    }

    override fun pressesChanged(presses: Set<*>, withEvent: UIPressesEvent?) {
        // Analog buttons of an MFi controller report their pressure through this phase. Compose
        // has no event for it, so it is swallowed rather than left to UIResponder's default
        // implementation, which would send it up the chain behind Compose's back.
        val forwarding = onKeyboardPresses(presses, withEvent)
        if (forwarding.passThrough.isNotEmpty()) {
            super.pressesChanged(forwarding.passThrough, withEvent)
        }
        forwarding.onForwardingFinished()
    }

    override fun touchesBegan(touches: Set<*>, withEvent: UIEvent?) {
        withEvent?.let { event -> onTouchesEvent(touches, event, TouchesEventKind.BEGAN) }
        super.touchesBegan(touches, withEvent)
    }

    override fun touchesMoved(touches: Set<*>, withEvent: UIEvent?) {
        withEvent?.let { event -> onTouchesEvent(touches, event, TouchesEventKind.MOVED) }
        super.touchesMoved(touches, withEvent)
    }

    override fun touchesEnded(touches: Set<*>, withEvent: UIEvent?) {
        withEvent?.let { event -> onTouchesEvent(touches, event, TouchesEventKind.ENDED) }
        super.touchesEnded(touches, withEvent)
    }

    override fun touchesCancelled(touches: Set<*>, withEvent: UIEvent?) {
        touches?.let { t -> onCancelAllTouches(t) }
        super.touchesCancelled(touches, withEvent)
    }

    override fun hitTest(point: CValue<CGPoint>, withEvent: UIEvent?): UIView? {
        if (!isPointInsideInteractionBounds(point)) {
            if (isInterceptingOutsideEvents) {
                return this
            }
            return null
        }
        val interopViewHitTest = hitTestInteropView(point)
        if (interopViewHitTest != null && interopViewHitTest.superview != this) {
            // Interop view is located inside another container.
            return null
        }
        return super.hitTest(point, withEvent)
    }

    fun dispose() {
        endEditing(force = true)
        hitTestInteropView = { null }
        isPointInsideInteractionBounds = { false }
        onKeyboardPresses = { _, _ -> TvPressForwarding.None }
        onOutsidePointerEvent = {}
        onTouchesEvent = { _, _, _ -> PointerEventResult() }
        onCancelAllTouches = {}
    }
}

/**
 * A simple background view for tvOS that handles touch events directed at interop views located
 * below the rendering canvas. Unlike the iOS [BackgroundInputView], this does not use gesture
 * recognizers.
 */
private class TvBackgroundInputView(
    private var onMovedToWindow: () -> Unit,
    private var onLayoutSubviews: () -> Unit,
    private var hitTestInteropView: (point: CValue<CGPoint>) -> UIView?,
    private var isPointInsideInteractionBounds: (CValue<CGPoint>) -> Boolean,
) : UIView(CGRectZero.readValue()) {

    private var onAppeared: (() -> Unit)? = null

    fun runOnceOnAppeared(block: () -> Unit) {
        onAppeared = {
            onAppeared = null
            block()
        }

        runOnAppearedIfEligible()
    }

    private fun runOnAppearedIfEligible() {
        if (window != null && !CGRectIsEmpty(frame)) {
            onAppeared?.invoke()
        }
    }

    override fun canBecomeFocused(): Boolean = false

    override fun layoutSubviews() {
        super.layoutSubviews()

        onLayoutSubviews()
        runOnAppearedIfEligible()
    }

    override fun didMoveToWindow() {
        super.didMoveToWindow()

        onMovedToWindow()
        setNeedsLayout()
    }

    init {
        // multipleTouchEnabled not available on tvOS K/N bindings
        setAccessibilityElements(emptyList<Any>())
    }

    override fun hitTest(point: CValue<CGPoint>, withEvent: UIEvent?): UIView? {
        if (!isPointInsideInteractionBounds(point)) {
            return null
        }
        return hitTestInteropView(point)
            ?.takeIf { it.superview == this }
            ?.let { it.hitTest(point = convertPoint(point, toView = it), withEvent = withEvent) }
    }

    fun dispose() {
        endEditing(force = true)
        hitTestInteropView = { null }
        isPointInsideInteractionBounds = { false }
        onLayoutSubviews = {}
        onMovedToWindow = {}
        onAppeared = null
    }
}

internal class ComposeSceneMediator(
    private val onFocusBehavior: OnFocusBehavior,
    private val isClearFocusOnMouseDownEnabled: Boolean,
    private val focusedViewsList: FocusedViewsList?,
    private val windowContext: PlatformWindowContext,
    private val architectureComponentsOwner: PlatformArchitectureComponentsOwner,
    private val coroutineContext: CoroutineContext,
    private val redrawer: MetalRedrawer,
    private val navigationEventInput: TvBackNavigationEventInput,
    private val pressDispatchLog: TvPressDispatchLog,
    private val touchOracle: SiriRemoteTouchOracle,
    interfaceOrientationState: State<InterfaceOrientation>,
    composeSceneFactory:
        (
            invalidate: () -> Unit,
            platformContext: PlatformContext,
            frameRecomposer: FrameRecomposer,
        ) -> ComposeScene,
) {
    private var onPreviewKeyEvent: (KeyEvent) -> Boolean = { false }
    private var onKeyEvent: (KeyEvent) -> Boolean = { false }

    // Key repeat state: repeatedly dispatch KeyDown while a key is held
    private val repeatingKeys = mutableMapOf<Long, Job>()

    // Id of the Select press whose KeyDown was swallowed to open the tvOS keyboard; the matching
    // KeyUp must be swallowed too so the scene never observes an unmatched KeyUp. Keyed by press
    // id rather than a flag, so a KeyUp delivered elsewhere (e.g. to the keyboard overlay) can't
    // make the next Select press on another element disappear.
    private var swallowedSelectKeyId: Long? = null

    // Tracks each Siri Remote indirect contact for swipe-to-focus.
    private val indirectTouches = mutableMapOf<Int, IndirectTouchState>()

    // Guards the display-link evaluation against re-entering itself through a dispatched key.
    private var isEvaluatingOracleSample = false

    // Kept as a value so the same instance can be removed from the shared oracle on dispose.
    private val oracleSampleListener: (Offset?) -> Unit = ::onOracleSample

    // Whether this mediator holds a sampling session of the shared oracle, and the token of that
    // session.
    private var isSamplingOracle = false
    private var oracleSamplingToken = 0

    // The momentum burst that keeps moving focus after a swipe, the contact it came from, and the
    // sampling session it holds of its own, since it outlives that contact. The chain of
    // consecutive swipes survives contacts, so a quick second swipe can be boosted.
    private var momentumBurst: TvMomentumBurst? = null
    /** When the last focus move of the burst in progress went out, its own move included. */
    private var lastBurstStepAt = 0.0
    private var burstSource: IndirectTouchState? = null
    private var burstBusyRetries = 0
    private var isSamplingFling = false
    private var flingSamplingToken = 0

    // Counts every call of cancelMomentum, dispose included, so a dispatch can tell that a handler
    // it ran cancelled the momentum or disposed this mediator underneath it.
    private var momentumCancellations = 0
    private val swipeChain = TvSwipeChainTracker()

    // The fast scrolling mode in progress, if any. It holds a sampling session of its own, which
    // drives its display link, and the ids of presses that never reach the scene while it runs.
    private var fastScroll: FastScrollSession? = null
    private val swallowedFastScrollKeyIds = mutableSetOf<Long>()

    // A lookup of a fast scroll container that found none at the lift of a swipe, retried while the
    // burst of that swipe keeps the display link running: focus invalidation may be pending.
    private var pendingFastEntryKey: Key? = null
    private val fastEntryRetries = TvRetryBudget(FAST_ENTRY_LOOKUP_RETRY_FRAMES)
    private var platformScreenReader =
        object : PlatformScreenReader {
            override var isActive by mutableStateOf(false)
        }

    private val isActive
        get() = coroutineContext.isActive

    private val viewConfiguration: ViewConfiguration =
        object : ViewConfiguration by PlatformContext.DefaultViewConfiguration {
            override val touchSlop: Float
                get() = with(screenDensity) { CUPERTINO_TOUCH_SLOP.dp.toPx() }
        }

    // TODO: It must be shared between Compose instances.
    //  It's supposed to be stored in platform's root view or window.
    val frameRecomposer = FrameRecomposer(coroutineContext, redrawer::setNeedsRedraw)

    private val sceneRenderingScope = SingleComposeSceneRenderingScope(redrawer::setNeedsRedraw)

    private val scene: ComposeScene by lazy {
        composeSceneFactory(
                sceneRenderingScope::onSceneInvalidation,
                PlatformContextImpl(),
                frameRecomposer,
            )
            .also {
                // Single owner of the 10-foot density rule; call sites pass the plain UIKit scale.
                it.density = tvSceneDensity(screenDensity, it.density.fontScale)
            }
    }

    private var composeSceneSize: IntSize?
        get() = scene.size
        set(value) {
            if (isActive) {
                scene.size = value
                if (value != null) {
                    windowInsetsManager.sceneSize.value = value
                }
            }
        }

    /**
     * Density used by the Compose scene for dp/px conversions within Compose.
     *
     * This value is intentionally separate from [screenDensity] so we can support setting custom
     * composeSceneDensity without regressions (merging [screenDensity] and [composeSceneDensity]
     * into one causes rendering and interaction issues because they are semantically different).
     */
    var composeSceneDensity: Density
        get() = scene.density
        set(value) {
            if (isActive) {
                scene.density = value
            }
        }

    /**
     * Density of the hosting UIKit screen.
     *
     * This value is intentionally separate from [composeSceneDensity] so we can support setting
     * composeSceneDensity without regressions.
     */
    val screenDensity: Density
        get() = _overlayView.density

    var layoutDirection: LayoutDirection
        get() = scene.layoutDirection
        set(value) {
            if (isActive) {
                scene.layoutDirection = value
            }
        }

    var compositionLocalContext: CompositionLocalContext?
        get() = scene.compositionLocalContext
        set(value) {
            if (isActive) {
                scene.compositionLocalContext = value
            }
        }

    val hasInteropViews: Boolean
        get() = interopContainer.hasInteropViews

    /**
     * Primary view to handle user input from the Siri Remote touch surface. Also used as a root
     * container view for accessibility.
     */
    private val _overlayView =
        TvOverlayInputView(
            hitTestInteropView = ::hitTestInteropView,
            isPointInsideInteractionBounds = ::isPointInsideInteractionBounds,
            onTouchesEvent = ::onTouchesEvent,
            onCancelAllTouches = ::onCancelAllTouches,
            onKeyboardPresses = ::onKeyboardPresses,
        )

    val overlayView: UIView
        get() = _overlayView

    /**
     * A holder for interop views that located below the Metal canvas. The view handles user touches
     * that occur only over the interop views located on it.
     */
    private val _backgroundView =
        TvBackgroundInputView(
            onMovedToWindow = ::focusOverlayViewIfNeeded,
            onLayoutSubviews = ::updateLayout,
            hitTestInteropView = ::hitTestInteropView,
            isPointInsideInteractionBounds = ::isPointInsideInteractionBounds,
        )

    val backgroundView: UIView
        get() = _backgroundView

    /** Container for managing UIKitView and UIKitViewController */
    private val interopContainer =
        UIKitInteropContainer(
            overlayContainer = _overlayView,
            backgroundContainer = _backgroundView,
            requestRedraw = redrawer::setNeedsRedraw,
        )

    private val windowInsetsManager =
        UIKitWindowInsetsManager(
            windowInsetsViews =
                listOf({ _overlayView }, { windowContext.window?.rootViewController?.view }),
            interfaceOrientation = interfaceOrientationState,
        )

    private val tvOSTextInputService =
        TvOSTextInputService(view = _overlayView, focusedViewsList = focusedViewsList)

    /**
     * A callback to define whether the precondition for the user input view hit test is met.
     *
     * @param point Point in the interaction view coordinate space.
     */
    private fun isPointInsideInteractionBounds(point: CValue<CGPoint>) =
        interactionBounds.contains(point.toDpOffset().toOffset(screenDensity).round())

    private val semanticsOwnerListener by lazy {
        SemanticsOwnerListenerImpl(
            view = _overlayView,
            coroutineContext = coroutineContext,
            performEscape = {
                val down = onKeyboardEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
                val up = onKeyboardEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp))

                down || up
            },
            onScreenReaderActive = { platformScreenReader.isActive = it },
        )
    }

    var isFocusEnabled: Boolean
        get() = semanticsOwnerListener.isEnabled
        set(value) {
            semanticsOwnerListener.isEnabled = value
            if (value) {
                focusOverlayViewIfNeeded()
            } else {
                _overlayView.resignFirstResponder()
            }
        }

    val hasInvalidations: Boolean
        get() =
            frameRecomposer.hasPendingWork() ||
                scene.hasInvalidations() ||
                isLayoutTransitionAnimating ||
                semanticsOwnerListener.hasInvalidations

    init {
        coroutineContext.job.invokeOnCompletion { dispose() }
        touchOracle.addSampleListener(oracleSampleListener)
    }

    private fun hitTestInteropView(point: CValue<CGPoint>): UIView? =
        point.useContents {
            // Same space as pointer positions and interop placement: points times the UIKit scale.
            val position = uiKitPointToScenePixels(toDpOffset(), screenDensity)
            val interopView = scene.hitTestInteropView(position)

            // Find a group of a holder associated with a given interop view or view controller
            interopView?.let { interopContainer.groupForInteropView(it) }
        }

    private fun onCancelAllTouches(touches: Set<*>) {
        redrawer.ongoingInteractionEventsCount -= touches.count()
        // Only the cancelled contacts are forgotten: another contact of the same gesture may
        // still be live and must keep its verdict.
        touches.forEach {
            indirectTouches.remove((it as UITouch).hashCode())?.let { state ->
                if (state.fastScrollContact) liftFastScrollContact(state, CACurrentMediaTime())
            }
        }
        cancelMomentum("cancelled")
        updateIndirectSampling()
        scene.cancelPointerInput()
    }

    /**
     * Recognizes Siri Remote swipes from indirect contacts and converts them to directional key
     * events. Those contacts are never forwarded to the Compose pointer input pipeline.
     *
     * When [touchOracle] reports a controller the contact is tracked in the absolute clickpad
     * space, so a movement that starts on the outer ring of arrow buttons is ignored the way native
     * tvOS ignores it, and the swipe is dispatched as soon as it is long enough. Without a
     * controller (the simulator, or the first frames before the remote connects) the relative UIKit
     * location is the only signal and the contact is evaluated once, when it ends.
     */
    private fun onIndirectTouchEvent(touch: UITouch, eventKind: TouchesEventKind) {
        val key = touch.hashCode()
        when (eventKind) {
            TouchesEventKind.BEGAN -> {
                // The momentum of the previous swipe keeps running under a new finger until that
                // finger swipes. Any contact whose terminal event never arrived stops sampling.
                swipeDebug {
                    "SWIPE remote category=${touchOracle.productCategory} hasRing=${touchOracle.hasRing}"
                }
                indirectTouches.values.forEach { it.flingTrackingClosed = true }
                val state =
                    if (touchOracle.isAvailable) {
                        val oraclePosition = touchOracle.position()
                        if (oraclePosition == null) {
                            // No sample of this contact yet: stay pending until one arrives, so the
                            // ring gate still applies instead of dropping to the ungated fallback.
                            swipeDebug { "SWIPE began verdict=pending" }
                            IndirectTouchState(
                                origin = Offset.Zero,
                                startTimestamp = touch.timestamp,
                                beginTimestamp = touch.timestamp,
                                usesOracle = true,
                                verdict = IndirectTouchVerdict.PENDING,
                            )
                        } else {
                            IndirectTouchState(
                                    origin = oraclePosition,
                                    startTimestamp = touch.timestamp,
                                    beginTimestamp = touch.timestamp,
                                    usesOracle = true,
                                    verdict = IndirectTouchVerdict.PENDING,
                                )
                                .also {
                                    resolveIndirectTouchOrigin(it, oraclePosition, touch.timestamp)
                                }
                        }
                    } else {
                        val position = touch.offsetInView(_backgroundView, screenDensity.density)
                        swipeDebug {
                            "SWIPE began origin=(${position.x}, ${position.y}) r=0.0 verdict=fallback"
                        }
                        IndirectTouchState(
                            origin = position,
                            startTimestamp = touch.timestamp,
                            beginTimestamp = touch.timestamp,
                            usesOracle = false,
                            verdict = IndirectTouchVerdict.CANDIDATE,
                        )
                    }
                indirectTouches[key] = state
                fastScroll?.let {
                    // A finger landing during fast scroll pans and flicks it. It never swipes.
                    state.fastScrollContact = true
                    it.physics.onTouchDown(CACurrentMediaTime())
                    swipeDebug { "FAST touch down" }
                }
                updateIndirectSampling()
            }
            TouchesEventKind.MOVED -> {
                val state = indirectTouches[key] ?: return
                if (!state.usesOracle || state.fastScrollContact) return
                val position = touchOracle.position()
                if (position == null) {
                    swipeDebug { "SWIPE moved sample=null" }
                    return
                }
                // The display link runs the same evaluation on every frame; this call is
                // idempotent and only keeps the contact moving when UIKit is the earlier signal.
                evaluateIndirectSample(state, position, touch.timestamp, logSample = true)
            }
            TouchesEventKind.ENDED -> {
                val state = indirectTouches.remove(key) ?: return
                updateIndirectSampling()
                if (state.fastScrollContact) {
                    liftFastScrollContact(state, CACurrentMediaTime())
                    return
                }
                if (state.usesOracle) {
                    if (state.verdict == IndirectTouchVerdict.PENDING) {
                        // The only sample of this contact arrives at its end: an origin with no
                        // movement observed in oracle space, so nothing is dispatched.
                        touchOracle.position()?.let {
                            resolveIndirectTouchOrigin(state, it, touch.timestamp)
                        }
                    }
                } else if (state.verdict == IndirectTouchVerdict.CANDIDATE) {
                    // Fallback only: the relative location is meaningful just once, at the end.
                    evaluateIndirectTouch(
                        state = state,
                        position = touch.offsetInView(_backgroundView, screenDensity.density),
                        timestamp = touch.timestamp,
                    )
                }
                // A lifted finger can no longer be a click, so the hold is not waited out here:
                // it only has to cover the press race while the finger is still down.
                if (state.verdict == IndirectTouchVerdict.ARMED) {
                    dispatchArmedIndirectTouch(state, touch.timestamp)
                }
                handleIndirectLift(state, touch.timestamp)
            }
        }
    }

    /**
     * Decides the origin and the verdict of a pending oracle contact from [sample], its first known
     * position. The contact keeps the timestamp of its BEGAN as start, so its duration is measured
     * from the finger landing rather than from the first sample.
     */
    private fun resolveIndirectTouchOrigin(
        state: IndirectTouchState,
        sample: Offset,
        timestamp: Double,
    ) {
        state.origin = sample
        state.restAnchor = sample
        state.lastSignificantMoveTime = timestamp
        val radius = hypot(sample.x, sample.y)
        state.minRadius = radius
        state.ringOrigin = isRingOrigin(sample.x, sample.y, touchOracle.hasRing)
        state.verdict =
            if (state.ringOrigin) {
                IndirectTouchVerdict.RING_CANDIDATE
            } else {
                IndirectTouchVerdict.CANDIDATE
            }
        swipeDebug {
            val verdict =
                if (state.verdict == IndirectTouchVerdict.RING_CANDIDATE) {
                    "ring-candidate"
                } else {
                    "candidate"
                }
            val sinceBegin = (timestamp - state.beginTimestamp) * 1000.0
            "SWIPE origin=(${sample.x}, ${sample.y}) r=$radius verdict=$verdict " +
                "t=${sinceBegin}ms"
        }
    }

    /**
     * Runs the origin resolution or the swipe evaluation of one oracle contact for [sample],
     * whichever its verdict calls for. Called both from UIKit's sparse MOVED callbacks and from
     * every display-link tick, so it must stay idempotent.
     */
    private fun evaluateIndirectSample(
        state: IndirectTouchState,
        sample: Offset,
        timestamp: Double,
        logSample: Boolean,
    ) {
        if (!state.usesOracle || state.fastScrollContact) return
        if (!state.flingTrackingClosed) {
            when (state.verdict) {
                IndirectTouchVerdict.CANDIDATE,
                IndirectTouchVerdict.RING_CANDIDATE,
                IndirectTouchVerdict.ARMED,
                // A dispatched contact keeps being sampled: the frames it is still moving in are
                // what its travel and its reversal are measured from.
                IndirectTouchVerdict.DISPATCHED -> {
                    state.flingSamples.add(sample.x, sample.y, timestamp)
                    val previous = state.lastSample
                    if (
                        previous != null &&
                            hypot(sample.x - previous.x, sample.y - previous.y) >
                                REST_ANCHOR_TOLERANCE
                    ) {
                        state.lastSampleMoveTime = timestamp
                    }
                    state.lastSample = sample
                    state.lastSampleTime = timestamp
                    // A sample after a dropped one: the contact is live, so the lift is still ahead.
                    if (!state.liftHandled) state.liftSeen = false
                    state.sampleCount++
                    if (
                        state.verdict == IndirectTouchVerdict.ARMED ||
                            state.verdict == IndirectTouchVerdict.DISPATCHED
                    ) {
                        updateSwipeTravel(state, sample, timestamp)
                    }
                }
                else -> {}
            }
        }
        when (state.verdict) {
            IndirectTouchVerdict.PENDING ->
                // First sample of this contact: it is the origin, not a movement.
                resolveIndirectTouchOrigin(state, sample, timestamp)
            IndirectTouchVerdict.CANDIDATE,
            IndirectTouchVerdict.RING_CANDIDATE -> {
                updateIndirectMinRadius(state, sample)
                evaluateIndirectTouch(state, sample, timestamp, logSample)
                if (state.verdict == IndirectTouchVerdict.ARMED && !state.flingTrackingClosed) {
                    updateSwipeTravel(state, sample, timestamp)
                }
            }
            IndirectTouchVerdict.ARMED -> {
                updateIndirectMinRadius(state, sample)
                holdArmedIndirectTouch(state, timestamp)
                updateMomentum(state)
            }
            IndirectTouchVerdict.DISPATCHED -> updateDispatchedContact(state, sample, timestamp)
            else -> {}
        }
    }

    /**
     * Keeps [IndirectTouchState.minRadius] at the smallest radius the contact was seen at since its
     * origin was last anchored, which is what tells a ring contact that crossed the centre pad from
     * one that stayed on the ring.
     */
    private fun updateIndirectMinRadius(state: IndirectTouchState, sample: Offset) {
        val radius = hypot(sample.x, sample.y)
        if (radius >= state.minRadius) return
        val wasShort = !ringOriginMayArm(state.minRadius)
        state.minRadius = radius
        if (state.ringOrigin && wasShort && ringOriginMayArm(radius)) {
            swipeDebug { "SWIPE ring crossing r=$radius" }
        }
    }

    /**
     * Feeds one display-link sample of the clickpad to every live oracle contact. UIKit reports
     * indirect movement sparsely, so this is what catches the origin of a contact in time and what
     * dispatches a swipe as soon as it is long enough.
     */
    private fun onOracleSample(sample: Offset?) {
        if (isEvaluatingOracleSample) return
        if (indirectTouches.isEmpty() && momentumBurst == null && fastScroll == null) return
        isEvaluatingOracleSample = true
        try {
            val timestamp = CACurrentMediaTime()
            // Fast scrolling runs first: it reads the contacts it owns and the frame it scrolls in.
            stepFastScroll(timestamp, sample)
            retryFastScrollEntry()
            // A burst runs while the pad reports no finger, i.e. exactly when there is no sample.
            stepBurst(timestamp)
            // Runs on every tick, empty pad included: a contact whose ENDED is lost while the pad
            // keeps reporting no finger (e.g. the remote disconnects mid-gesture) would otherwise
            // never be dropped, and its sampling session would keep the display link running.
            purgeStaleIndirectTouches(timestamp)
            if (sample != null) {
                for (state in indirectTouches.values) state.emptyPadTicks = 0
            }
            if (sample == null) {
                // The pad is empty, so the dispatched contacts are lifted whether or not their
                // ENDED has arrived yet, and the next samples are another finger's. The momentum is
                // still earned from what they recorded before this frame.
                for (state in indirectTouches.values.toList()) {
                    // A dispatch can dispose this mediator, which clears every contact.
                    if (indirectTouches.isEmpty()) break
                    // One empty tick can be a dropped sample, so the lift waits for a second.
                    state.emptyPadTicks++
                    if (!emptyPadIsLift(state.emptyPadTicks)) continue
                    handleIndirectLift(state, timestamp)
                    if (state.verdict == IndirectTouchVerdict.DISPATCHED) {
                        state.flingTrackingClosed = true
                    }
                }
                return
            }
            // A dispatch runs key event handlers, which may end contacts, so the states are
            // snapshotted before they are evaluated.
            for (state in indirectTouches.values.toList()) {
                // A dispatch can dispose this mediator, which clears every contact.
                if (indirectTouches.isEmpty()) break
                evaluateIndirectSample(state, sample, timestamp, logSample = false)
            }
        } finally {
            isEvaluatingOracleSample = false
        }
    }

    /**
     * Drops the contacts of [timestamp] whose terminal event never arrived, so a lost ENDED does
     * not keep the display link polling for the rest of the process. Contacts that already reached
     * a terminal verdict are dropped as soon as they can no longer dispatch.
     */
    private fun purgeStaleIndirectTouches(timestamp: Double) {
        var removed = false
        val iterator = indirectTouches.values.iterator()
        while (iterator.hasNext()) {
            val state = iterator.next()
            val maxAge =
                if (state.fastScrollContact) FAST_SCROLL_CONTACT_MAX_AGE_S
                else when (state.verdict) {
                    IndirectTouchVerdict.CANCELLED -> SWIPE_MAX_DURATION_S + DISPATCH_HOLD_S
                    // A dispatched contact is only in this map while its finger is still down, and
                    // its later samples carry the travel its momentum is sized from, so it is kept
                    // for as long as any live contact. The cost is that a lost ENDED now holds the
                    // sampling session open for the whole of that age rather than for the shorter
                    // dispatch window.
                    else -> INDIRECT_CONTACT_MAX_AGE_S
                }
            // The tick stamps with CACurrentMediaTime and the contacts with UITouch.timestamp,
            // both of which are the system uptime.
            val ageFrom =
                if (state.fastScrollContact || state.verdict == IndirectTouchVerdict.CANCELLED) {
                    state.beginTimestamp
                } else {
                    // A finger that rests keeps its swipe, so the age counts from its last movement.
                    // A contact whose ENDED was lost produces no movement, so it still goes.
                    maxOf(state.beginTimestamp, state.lastSampleMoveTime)
                }
            if (timestamp - ageFrom > maxAge) {
                iterator.remove()
                removed = true
                if (state.fastScrollContact) liftFastScrollContact(state, timestamp)
            }
        }
        if (removed) {
            updateIndirectSampling()
        }
    }

    /** Samples the pad per frame exactly while at least one indirect contact is live. */
    private fun updateIndirectSampling() {
        val shouldSample = indirectTouches.isNotEmpty()
        if (shouldSample == isSamplingOracle) return
        isSamplingOracle = shouldSample
        if (shouldSample) {
            oracleSamplingToken = touchOracle.beginSampling()
        } else {
            touchOracle.endSampling(oracleSamplingToken)
        }
    }

    /**
     * Moves [state] out of [IndirectTouchVerdict.CANDIDATE] if the contact reached [position] at
     * [timestamp] is a swipe, or if it can no longer become one.
     */
    private fun evaluateIndirectTouch(
        state: IndirectTouchState,
        position: Offset,
        timestamp: Double,
        logSample: Boolean = true,
    ) {
        if (updateIndirectRest(state, position, timestamp)) return
        val dx = position.x - state.origin.x
        val dy = position.y - state.origin.y
        val elapsed = timestamp - state.startTimestamp
        if (logSample) {
            swipeDebug { "SWIPE moved d=($dx, $dy) t=${elapsed * 1000.0}ms" }
        }
        if (isClickpadPressOverlapping(state.beginTimestamp, timestamp)) {
            state.verdict = IndirectTouchVerdict.CANCELLED
            swipeDebug { "SWIPE cancelled reason=press" }
            return
        }
        // A slow contact keeps accumulating travel: once it covers the gate it moves focus as a
        // drag, one row and no momentum. Only the relative fallback, which cannot follow a finger,
        // gives up on it. A finger that stops is re-anchored by the rest detection above.
        val slow = elapsed > SWIPE_MAX_DURATION_S
        if (slow && !state.usesOracle) {
            state.verdict = IndirectTouchVerdict.CANCELLED
            swipeDebug { "SWIPE cancelled reason=duration" }
            return
        }
        val distance =
            if (state.usesOracle) {
                SWIPE_DISTANCE_NORMALIZED
            } else {
                with(screenDensity) { SWIPE_DISTANCE_FALLBACK_DP.dp.toPx() }
            }
        val absDx = abs(dx)
        val absDy = abs(dy)
        val dominant = maxOf(absDx, absDy)
        val other = minOf(absDx, absDy)
        // A contact that continues a series of swipes may drift off the series' axis.
        val seriesKey = if (state.usesOracle) seriesKeyOf(state, dx, dy) else null
        if (seriesKey != null) {
            if (displacementAlongKey(seriesKey, dx, dy) < distance) return
        } else if (dominant < distance || dominant < SWIPE_AXIS_DOMINANCE * other) {
            return
        }
        // A contact that started on the ring only swipes once it crossed into the centre pad, the
        // way a straight swipe from one edge of the ring to the opposite edge does. A circular drag
        // around the ring keeps its radius, so it never gets here. The crossing radius is
        // provisional pending device calibration: a ring click's roll is expected to stay above it
        // and below the distance gate, and the press latch covers the rest.
        if (state.ringOrigin && !ringOriginMayArm(state.minRadius)) {
            return
        }
        // Sign convention for dy differs by source: the oracle's dy is the GameController dpad's
        // absolute clickpad y, which is positive toward the top of the remote, so a positive dy
        // is an upward swipe. The fallback dy is a UIKit relative view location, positive
        // downward as usual for screen coordinates, so a positive dy there is a downward swipe.
        val key =
            if (seriesKey != null) {
                seriesKey
            } else if (absDx >= absDy) {
                if (dx > 0) Key.DirectionRight else Key.DirectionLeft
            } else if (state.usesOracle) {
                if (dy > 0) Key.DirectionUp else Key.DirectionDown
            } else {
                if (dy > 0) Key.DirectionDown else Key.DirectionUp
            }
        state.verdict = IndirectTouchVerdict.ARMED
        state.armedKey = key
        state.armedAt = timestamp
        state.dragOnly = slow
        if (!state.usesOracle) {
            // The relative location is in pixels: the pad units of the oracle are recovered from
            // the distance gate the two sources share.
            state.maxTravel = dominant / screenDensity.density / FALLBACK_DP_PER_PAD_UNIT
        }
        swipeDebug {
            "SWIPE armed ${directionName(key)}${if (slow) " drag" else ""}" +
                if (seriesKey != null) " series" else ""
        }
    }

    /**
     * Holds an armed swipe until [DISPATCH_HOLD_S] elapsed, so a Select press that lands right
     * after the contact travelled far enough cancels the swipe instead of firing both.
     */
    private fun holdArmedIndirectTouch(state: IndirectTouchState, timestamp: Double) {
        if (isClickpadPressOverlapping(state.beginTimestamp, timestamp)) {
            state.verdict = IndirectTouchVerdict.CANCELLED
            swipeDebug { "SWIPE cancelled reason=press" }
            return
        }
        if (timestamp - state.armedAt < DISPATCH_HOLD_S) return
        dispatchIndirectSwipe(state, timestamp)
    }

    /** Dispatches an armed swipe unless a clickpad press claimed the contact meanwhile. */
    private fun dispatchArmedIndirectTouch(state: IndirectTouchState, timestamp: Double) {
        if (isClickpadPressOverlapping(state.beginTimestamp, timestamp)) {
            state.verdict = IndirectTouchVerdict.CANCELLED
            swipeDebug { "SWIPE cancelled reason=press" }
            return
        }
        dispatchIndirectSwipe(state, timestamp)
    }

    /**
     * Keeps [IndirectTouchState.maxTravel] at the furthest [state] went from its origin along the
     * axis of its armed key, in the direction of that key.
     */
    private fun updateSwipeTravel(state: IndirectTouchState, sample: Offset, timestamp: Double) {
        val key = state.armedKey ?: return
        val travel = travelAlongKey(state, key, sample)
        val turnSample = state.maxTravelSample
        if (travel > state.maxTravel) {
            state.maxTravel = travel
            state.maxTravelSample = sample
            state.maxTravelTime = timestamp
        } else if (
            turnSample != null &&
                hypot(sample.x - turnSample.x, sample.y - turnSample.y) <= REST_ANCHOR_TOLERANCE
        ) {
            // A finger resting where it went furthest: a turn starts when it leaves that point.
            state.maxTravelTime = timestamp
        }
        state.flingSamples
            .velocityAlong(
                isHorizontalFlingKey(key),
                FLING_VELOCITY_WINDOW_S,
                PEAK_SPEED_MIN_SPAN_S,
            )
            ?.let {
                val speed = signedSpeedAlongKey(key, it)
                if (speed > state.peakSpeed) state.peakSpeed = speed
            }
        // A flick the pad samples only two or three times never fills the window above: its
        // average speed since the swipe started stands in for it.
        val elapsed = timestamp - state.startTimestamp
        if (elapsed >= AVERAGE_SPEED_MIN_ELAPSED_S) {
            val average = (travel / elapsed).toFloat()
            if (average > state.peakSpeed) state.peakSpeed = average
        }
    }

    /**
     * The signed travel of [state] from its origin to [sample] along the axis of [key], toward
     * [key].
     */
    private fun travelAlongKey(state: IndirectTouchState, key: Key, sample: Offset): Float =
        displacementAlongKey(key, sample.x - state.origin.x, sample.y - state.origin.y)

    /**
     * The direction of the series of swipes the contact [state] continues, if its displacement
     * [dx], [dy] runs that way within [SERIES_AXIS_DOMINANCE].
     */
    private fun seriesKeyOf(state: IndirectTouchState, dx: Float, dy: Float): Key? =
        seriesSwipeKey(swipeChain.continuingKey(state.beginTimestamp), dx, dy)

    /** Raises the rows of the burst [state] started to what its travel has earned by now. */
    private fun updateMomentum(state: IndirectTouchState) {
        if (state.flingTrackingClosed) return
        val burst = momentumBurst ?: return
        if (burstSource !== state) return
        val rows = swipeRows(state)
        burst.raiseRows(rows)
        swipeChain.onSwipeRows(rows)
    }

    /**
     * Follows the finger of a dispatched contact, the way the native focus engine follows a gliding
     * finger item by item. A finger that came back [DRAG_REVERSAL_TRAVEL] from the furthest point
     * it reached has turned: the contact starts over there, in the direction it now moves in.
     * Otherwise every [DRAG_STEP_TRAVEL] of further travel pays for one move, sent at once when the
     * momentum curve has not emitted it by then; a fast swipe's curve runs ahead of its finger, so
     * this only adds moves for a finger that keeps going past what the swipe earned.
     */
    private fun updateDispatchedContact(
        state: IndirectTouchState,
        sample: Offset,
        timestamp: Double,
    ) {
        if (state.flingTrackingClosed) return
        val key = state.dispatchedKey ?: return
        if (state.maxTravel - travelAlongKey(state, key, sample) >= DRAG_REVERSAL_TRAVEL) {
            restartContactAtTurn(state, sample, timestamp)
            return
        }
        updateMomentum(state)
        while (state.maxTravel >= state.dragNextTravel) {
            state.travelSteps++
            state.dragNextTravel += DRAG_STEP_TRAVEL
        }
        val burst = momentumBurst ?: return
        if (burstSource !== state) return
        if (state.travelSteps > burst.emitted) emitBurstStep(burst, timestamp, "DRAG")
    }

    /**
     * Starts [state] over at the furthest point it reached, as a candidate for a swipe in the
     * direction its finger now moves in. The momentum it drove is over, the ring verdict is read
     * where it turned, and the swipe timer runs from the turn, so a quick flick back earns momentum
     * and a slow return drags.
     */
    private fun restartContactAtTurn(state: IndirectTouchState, sample: Offset, timestamp: Double) {
        val turnSample = state.maxTravelSample
        val origin = turnSample ?: sample
        val turnTime = if (turnSample != null) state.maxTravelTime else timestamp
        swipeDebug {
            "SWIPE turned at=(${origin.x}, ${origin.y}) travel=${state.maxTravel} " +
                "t=${debugSeconds(timestamp)}"
        }
        if (burstSource === state) cancelMomentum("turned")
        reanchorIndirectTouch(state, origin, turnTime)
        state.ringOrigin = isRingOrigin(origin.x, origin.y, touchOracle.hasRing)
        state.minRadius = hypot(origin.x, origin.y)
        state.verdict =
            if (state.ringOrigin) {
                IndirectTouchVerdict.RING_CANDIDATE
            } else {
                IndirectTouchVerdict.CANDIDATE
            }
        state.armedKey = null
        state.dispatchedKey = null
        state.maxTravelSample = null
        state.dragOnly = false
        state.liftArmed = false
        state.travelSteps = 0
        state.dragNextTravel = 0f
        updateIndirectMinRadius(state, sample)
        evaluateIndirectTouch(state, sample, timestamp, logSample = false)
    }

    /**
     * The rows the swipe of [state] earns by now. The start offset is measured from the origin the
     * travel is measured from. The relative fallback has no absolute origin, so its start offset is
     * unknown and it earns no momentum.
     */
    private fun swipeRows(state: IndirectTouchState): Int {
        if (state.dragOnly) return 1
        if (state.liftArmed && !state.followsMomentumSwipe) return 1
        val key = state.armedKey ?: return 1
        val startOffset =
            if (state.usesOracle) {
                swipeStartOffset(
                    key,
                    if (isHorizontalFlingKey(key)) state.origin.x else state.origin.y,
                )
            } else {
                null
            }
        return momentumRowsFor(
            startOffset = startOffset,
            travel = state.maxTravel,
            peakSpeed = state.peakSpeed,
            followsMomentumSwipe = state.followsMomentumSwipe,
            quickContact = state.quickContact,
        )
    }

    /** The start offset of [state] for the debug log, `NaN` when unknown. */
    private fun debugStartOffset(state: IndirectTouchState): Float {
        val key = state.armedKey ?: return Float.NaN
        if (!state.usesOracle) return Float.NaN
        return swipeStartOffset(key, if (isHorizontalFlingKey(key)) state.origin.x else state.origin.y)
    }

    /**
     * Settles the momentum of [state] as its finger leaves the pad, once per contact: the travel
     * and the boost of a quick second swipe are final now. It raises the rows of its burst.
     */
    private fun handleIndirectLift(state: IndirectTouchState, timestamp: Double) {
        if (state.liftHandled) return
        if (!state.liftSeen) {
            // The empty pad calls this on every tick until the ENDED arrives, so the logging and
            // the lift-time arm happen once, and a contact that lift-armed is not armed twice.
            state.liftSeen = true
            state.quickContact =
                state.lastSampleTime - state.beginTimestamp <= CHAIN_QUICK_CONTACT_S
            logIndirectLift(state, timestamp)
            if (
                state.verdict == IndirectTouchVerdict.CANDIDATE ||
                    state.verdict == IndirectTouchVerdict.RING_CANDIDATE
            ) {
                armIndirectTouchAtLift(state, timestamp)
            }
        }
        if (state.verdict != IndirectTouchVerdict.DISPATCHED) return
        state.liftHandled = true
        state.flingTrackingClosed = true
        val key = state.dispatchedKey ?: return
        val boost = swipeChain.currentBoost()
        val rows = boostedRows(swipeRows(state), boost)
        swipeDebug {
            "MOMENTUM plan rows=$rows travel=${state.maxTravel} speed=${state.peakSpeed} " +
                "chained=${state.followsMomentumSwipe} quick=${state.quickContact} " +
                "boost=$boost startOffset=${debugStartOffset(state)} t=${debugSeconds(timestamp)}"
        }
        swipeChain.onSwipeRows(rows)
        swipeChain.onLift(timestamp)
        if (burstSource === state) momentumBurst?.raiseRows(rows)
        if (swipeChain.shouldEnterFastMode()) tryEnterFastScrollFromSwipe(key)
    }

    private fun logIndirectLift(state: IndirectTouchState, timestamp: Double) {
        swipeDebug {
            val last = state.lastSample
            val lastText = if (last == null) "none" else "${last.x},${last.y}"
            "SWIPE lift verdict=${state.verdict.name.lowercase()} " +
                "origin=${state.origin.x},${state.origin.y} last=$lastText " +
                "travel=${state.maxTravel} samples=${state.sampleCount} " +
                "dur=${(timestamp - state.beginTimestamp) * 1000.0}"
        }
    }

    /**
     * Arms and dispatches, as a one row swipe, a contact that lifted while still a candidate
     * because it was too short or too sparsely sampled to arm on its own: it moved at least
     * [SWIPE_LIFT_DISTANCE] along a dominant axis within [SWIPE_MAX_DURATION_S] of its origin.
     *
     * The click race is closed the way the armed contact that ends is: no clickpad press may be
     * held or have begun since shortly before the contact, by the same check that normal arming
     * uses, and the callers run it once per contact. A click's Select press lands while the finger
     * is still down, before this lift, so it is already in the press log by now.
     */
    private fun armIndirectTouchAtLift(state: IndirectTouchState, timestamp: Double) {
        if (!state.usesOracle || state.fastScrollContact) return
        val last = state.lastSample ?: return
        if (state.ringOrigin && !ringOriginMayArm(state.minRadius)) return
        val dx = last.x - state.origin.x
        val dy = last.y - state.origin.y
        val elapsed = state.lastSampleTime - state.startTimestamp
        val seriesKey = seriesKeyOf(state, dx, dy)
        val liftSwipe =
            if (seriesKey != null) {
                displacementAlongKey(seriesKey, dx, dy) >= SWIPE_LIFT_DISTANCE &&
                    elapsed <= SWIPE_MAX_DURATION_S
            } else {
                isLiftArmSwipe(dx, dy, elapsed, SWIPE_MAX_DURATION_S, SWIPE_AXIS_DOMINANCE)
            }
        if (!liftSwipe) return
        if (isClickpadPressOverlapping(state.beginTimestamp, timestamp)) {
            state.verdict = IndirectTouchVerdict.CANCELLED
            swipeDebug { "SWIPE cancelled reason=press" }
            return
        }
        val absDx = abs(dx)
        val absDy = abs(dy)
        val key =
            if (seriesKey != null) {
                seriesKey
            } else if (absDx >= absDy) {
                if (dx > 0) Key.DirectionRight else Key.DirectionLeft
            } else if (dy > 0) {
                Key.DirectionUp
            } else {
                Key.DirectionDown
            }
        state.verdict = IndirectTouchVerdict.ARMED
        state.armedKey = key
        state.armedAt = timestamp
        state.maxTravel =
            if (seriesKey != null) displacementAlongKey(seriesKey, dx, dy) else maxOf(absDx, absDy)
        state.liftArmed = true
        if (elapsed >= AVERAGE_SPEED_MIN_ELAPSED_S) {
            state.peakSpeed = (state.maxTravel / elapsed).toFloat()
        }
        swipeDebug {
            "SWIPE lift-arm ${directionName(key)} d=($dx, $dy) " +
                "t=${debugSeconds(timestamp)} elapsed=${elapsed * 1000.0}ms"
        }
        dispatchIndirectSwipe(state, timestamp)
    }

    /**
     * Runs the moves the burst in progress owes at [timestamp]. The remote stays authoritative: any
     * press, a finger that turns back and any move the scene did not consume end the momentum. A
     * tick that lands late catches up, within [MOMENTUM_MAX_STEPS_PER_TICK].
     */
    private fun stepBurst(timestamp: Double) {
        val burst = momentumBurst ?: return
        if (!isFocusEnabled) {
            // A move can open a layer above this scene, and the rest of the momentum belongs to
            // whatever is on top now rather than to the scene it covered.
            cancelMomentum("inactive")
            return
        }
        if (touchOracle.anyButtonPressed()) {
            cancelMomentum("press")
            return
        }
        if (isClickpadPressOverlapping(burst.startedAt, timestamp)) {
            cancelMomentum("press")
            return
        }
        if (isBurstSourceReversing(burst)) {
            burst.stop()
            cancelMomentum("reversal")
            return
        }
        // The curve is read half a frame ahead: a step due just after this tick goes out now rather
        // than a whole frame late.
        val due =
            minOf(
                burst.stepsDue(timestamp + MOMENTUM_TICK_LOOKAHEAD_S, lastBurstStepAt),
                MOMENTUM_MAX_STEPS_PER_TICK,
            )
        for (i in 0 until due) {
            if (!emitBurstStep(burst, timestamp, "MOMENTUM")) return
        }
        // While the finger of the burst is down its travel can still raise the rows, so a burst
        // that already emitted all of them is only over once that finger left.
        val source = burstSource
        val sourceLifted =
            source == null || source.liftHandled || !indirectTouches.containsValue(source)
        if (burst.isFinished && sourceLifted) {
            cancelMomentum("finished")
        }
    }

    /**
     * Sends one step of [burst] as a KeyDown/KeyUp pair, logged under [tag]. Returns `false` when
     * nothing more should go out on this tick: the burst was cancelled by a handler, the scene was
     * busy and the step is retried next tick, or the move was refused and the burst is over.
     */
    private fun emitBurstStep(burst: TvMomentumBurst, timestamp: Double, tag: String): Boolean {
        val key = burst.key
        swipeDebug { "$tag step ${burst.emitted + 1}/${burst.rows} t=${debugSeconds(timestamp)}" }
        val consumed =
            onKeyboardEvent(KeyEvent(key, KeyEventType.KeyDown, nativeEvent = burst.swipe))
        // The KeyDown can cancel the burst re-entrantly, but the KeyUp is still owed to balance
        // it, so it is sent before checking whether the burst is still the one in progress.
        onKeyboardEvent(KeyEvent(key, KeyEventType.KeyUp, nativeEvent = burst.swipe))
        // A dispatch runs key event handlers, which can dispose this mediator or cancel the burst.
        if (momentumBurst !== burst) return false
        if (!consumed) {
            // The focus system may be busy for a frame, which a retry covers; a move that is
            // still refused is a real focus edge.
            if (burstBusyRetries < MOMENTUM_BUSY_RETRIES) {
                burstBusyRetries++
                return false
            }
            cancelMomentum(
                "unconsumed step=${burst.emitted + 1}/${burst.rows} key=${directionName(key)}"
            )
            return false
        }
        burst.onStepEmitted()
        lastBurstStepAt = timestamp
        swipeChain.onFocusMoved()
        burstBusyRetries = 0
        return true
    }

    /**
     * `true` when the finger that started [burst] is still down and has clearly turned back against
     * the direction the momentum runs in. Once that contact ended nothing samples it any more. A
     * drag has no momentum to cut short: its finger is followed by travel, turn included.
     */
    private fun isBurstSourceReversing(burst: TvMomentumBurst): Boolean {
        val source = burstSource ?: return false
        if (source.flingTrackingClosed || source.dragOnly) return false
        val velocity =
            source.flingSamples.velocityAlong(
                isHorizontalFlingKey(burst.key),
                FLING_VELOCITY_WINDOW_S,
            ) ?: return false
        return signedSpeedAlongKey(burst.key, velocity) < -FLING_REVERSAL_SPEED
    }

    /** Ends the burst in progress, if any, and releases the sampling session it held. */
    private fun cancelMomentum(reason: String) {
        momentumCancellations++
        if (momentumBurst == null && !isSamplingFling) return
        if (momentumBurst != null) {
            swipeDebug { "MOMENTUM cancelled reason=$reason" }
        }
        momentumBurst = null
        burstSource = null
        burstBusyRetries = 0
        pendingFastEntryKey = null
        if (isSamplingFling) {
            isSamplingFling = false
            touchOracle.endSampling(flingSamplingToken)
        }
    }

    /**
     * Looks for a fast scroll container for the chain of swipes that just lifted and enters the mode
     * when there is one. Without one nothing changes and the burst goes on. A miss is retried on the
     * next ticks of that burst, since focus invalidation may be pending.
     */
    private fun tryEnterFastScrollFromSwipe(key: Key) {
        if (fastScroll != null || !isFocusEnabled) return
        val node = findFastScrollNode(key, "swipe")
        if (node != null) {
            startFastScroll(node, key, "swipe", swipeChain.momentumMoves, holdKeyId = null)
        } else if (momentumBurst != null) {
            pendingFastEntryKey = key
            fastEntryRetries.reset()
        }
    }

    /** Retries a lookup that found no fast scroll container at the lift, a few ticks at most. */
    private fun retryFastScrollEntry() {
        val key = pendingFastEntryKey ?: return
        if (
            fastScroll != null ||
                momentumBurst == null ||
                swipeChain.key != key ||
                !swipeChain.shouldEnterFastMode() ||
                !fastEntryRetries.tryConsume()
        ) {
            pendingFastEntryKey = null
            return
        }
        val node = findFastScrollNode(key, "swipe") ?: return
        pendingFastEntryKey = null
        startFastScroll(node, key, "swipe", swipeChain.momentumMoves, holdKeyId = null)
    }

    /**
     * Enters fast scrolling in place of the repeat of the held [key] that would be its eleventh
     * move. Returns `false` when there is no container to take it, and the hold keeps repeating.
     */
    private fun tryEnterFastScrollFromHold(key: Key, keyId: Long, moves: Int): Boolean {
        if (fastScroll != null || !isFocusEnabled || tvOSTextInputService.isKeyboardVisible) {
            return false
        }
        val node = findFastScrollNode(key, "hold") ?: return false
        return startFastScroll(node, key, "hold", moves, holdKeyId = keyId)
    }

    private fun findFastScrollNode(key: Key, source: String): RemoteFastScrollNode? {
        val direction = remoteSwipeDirection(key)
        // A disabled modifier must stay inert, so the node is asked once more whether it accepts.
        val node = scene.findRemoteFastScrollNode(direction)?.takeIf { it.accepts(direction) }
        if (node == null) {
            swipeDebug { "FAST lookup miss source=$source direction=$direction" }
        }
        return node
    }

    /** Takes focus on [node] and starts driving it. Returns `false`, with nothing changed, if it refused. */
    private fun startFastScroll(
        node: RemoteFastScrollNode,
        key: Key,
        source: String,
        moves: Int,
        holdKeyId: Long?,
    ): Boolean {
        val cancellations = momentumCancellations
        val began = scene.runRemoteInput { node.beginFastScroll() }
        // The callbacks of the app ran: they can dispose this mediator underneath the call.
        if (cancellations != momentumCancellations) {
            node.abortFastScroll()
            return false
        }
        if (!began) {
            swipeDebug { "FAST begin refused source=$source" }
            return false
        }
        val now = CACurrentMediaTime()
        val physics = TvFastScrollPhysics()
        val forward = isForwardKey(key)
        if (holdKeyId != null) physics.startFromHold(now, forward)
        else physics.startFromSwipe(now, forward)
        fastScroll =
            FastScrollSession(
                node = node,
                physics = physics,
                vertical = !isHorizontalFlingKey(key),
                holdKeyId = holdKeyId,
                samplingToken = touchOracle.beginSampling(),
                hadController = touchOracle.isAvailable,
                startedAt = now,
            )
        // Fingers already on the pad join the mode: they pan it, can stop it and never swipe. Their
        // first sample is a baseline, and a finger that rests while the drive key is held does not
        // stop the drive (the physics ignores rest while held).
        var adopted = false
        for (state in indirectTouches.values) {
            if (state.fastScrollLifted) continue
            state.fastScrollContact = true
            adopted = true
        }
        if (adopted) physics.onTouchDown(now)
        // The burst of the swipe that entered the mode is replaced by it.
        cancelMomentum("fast scroll")
        swipeDebug {
            val label = if (source == "swipe") "momentumMoves" else "moves"
            val swipesPart = if (source == "swipe") " momentumSwipes=${swipeChain.momentumSwipes}" else ""
            "FAST enter source=$source key=${directionName(key)} $label=$moves$swipesPart v=${physics.velocity}"
        }
        return true
    }

    /**
     * Drives fast scrolling for one display-link tick: feeds the fingers on the pad to the physics,
     * scrolls the container by what it asks for, and ends the mode when it says so.
     */
    private fun stepFastScroll(timestamp: Double, sample: Offset?) {
        val session = fastScroll ?: return
        val node = session.node
        if (!isFocusEnabled) {
            // A layer or another scene took the input: what is left of the mode is not ours.
            forceEndFastScroll("inactive", restore = true)
            return
        }
        if (!node.isFastScrolling) {
            // The node ended itself: it was detached, or the app took focus from it.
            forceEndFastScroll("abort", restore = false)
            return
        }
        if (!node.enabled) {
            forceEndFastScroll("disabled", restore = true)
            return
        }
        val physics = session.physics
        if (session.hadController && !session.controllerLost && !touchOracle.isAvailable) {
            session.controllerLost = true
            swipeDebug { "FAST controller lost" }
            releaseFastScrollHold(session, timestamp)
        }
        if (session.holdKeyId != null && timestamp - session.startedAt > FAST_HOLD_MAX_S) {
            // The release of the key never arrived: brake and exit instead of driving for ever.
            swipeDebug { "FAST hold watchdog" }
            releaseFastScrollHold(session, timestamp)
        }
        for (state in indirectTouches.values.toList()) {
            if (!state.fastScrollContact || state.fastScrollLifted) continue
            if (sample == null) {
                // The pad is empty: the finger left whether or not its ENDED arrived yet.
                liftFastScrollContact(state, timestamp)
            } else {
                feedFastScrollContact(session, state, sample, timestamp)
            }
        }
        if (session.exitReason != null) {
            attemptFastScrollExit(session)
            return
        }
        val stepGapS = timestamp - session.lastStepLogAt
        session.lastStepLogAt = timestamp
        if (stepGapS > FAST_SLOW_STEP_S && timestamp - session.lastSlowLogAt >= FAST_LOG_INTERVAL_S) {
            session.lastSlowLogAt = timestamp
            swipeDebug { "FAST slow dt=${(stepGapS * 1000.0).toLong()} t=${debugSeconds(timestamp)}" }
        }
        val dp = physics.step(timestamp)
        if (dp != 0f) {
            val density = scene.density.density
            session.lastRequested = dp
            val consumedPx =
                scene.runRemoteInput { node.fastScrollBy(dp * density, physics.velocity * density) }
            // The callbacks of the app ran: they can dispose this mediator underneath the call.
            if (fastScroll !== session) return
            physics.onConsumed(dp, consumedPx / density)
            val gotDp = consumedPx / density
            if (abs(dp - gotDp) > FAST_END_SLACK_DP &&
                timestamp - session.lastShortLogAt >= FAST_LOG_INTERVAL_S
            ) {
                session.lastShortLogAt = timestamp
                swipeDebug {
                    "FAST short req=$dp got=$gotDp v=${physics.velocity} t=${debugSeconds(timestamp)}"
                }
            }
        }
        syncEdgeWait(session, timestamp)
        if (physics.shouldExit) {
            session.exitReason = fastScrollExitReason(physics.hitEnd, physics.velocity)
            attemptFastScrollExit(session)
        }
    }

    /** Mirrors the edge wait of the physics to the node state and logs its transitions. */
    private fun syncEdgeWait(session: FastScrollSession, timestamp: Double) {
        val physics = session.physics
        val waiting = physics.isWaitingAtEdge
        if (waiting == session.edgeWaiting) return
        session.edgeWaiting = waiting
        session.node.setWaitingAtEdge(waiting)
        swipeDebug {
            val phase = if (waiting) {
                "start"
            } else {
                when (physics.lastEdgeWaitOutcome) {
                    TvEdgeWaitOutcome.Timeout -> "final"
                    TvEdgeWaitOutcome.Stopped -> "stop"
                    TvEdgeWaitOutcome.Reversed -> "reverse"
                    TvEdgeWaitOutcome.Slow -> "slow"
                    else -> "resume"
                }
            }
            "FAST edge wait $phase waited=${(physics.edgeWaitedS * 1000.0).toLong()} t=${debugSeconds(timestamp)}"
        }
    }

    /** Stops the held key from driving the mode; the release of that key is swallowed once. */
    private fun releaseFastScrollHold(session: FastScrollSession, timestamp: Double) {
        val keyId = session.holdKeyId ?: return
        session.holdKeyId = null
        swallowedFastScrollKeyIds.add(keyId)
        session.physics.onHoldReleased(timestamp)
    }

    private fun feedFastScrollContact(
        session: FastScrollSession,
        state: IndirectTouchState,
        sample: Offset,
        timestamp: Double,
    ) {
        if (!state.usesOracle) return
        if (state.verdict == IndirectTouchVerdict.PENDING) {
            resolveIndirectTouchOrigin(state, sample, timestamp)
        }
        updateIndirectMinRadius(state, sample)
        // A finger that began on the ring only counts once it crossed into the centre pad.
        if (state.ringOrigin && !ringOriginMayArm(state.minRadius)) return
        val physics = session.physics
        val wasMoving = physics.velocity != 0f
        physics.onTouchSample(fastScrollForwardPosition(sample, session.vertical), timestamp)
        if (wasMoving && physics.velocity == 0f) {
            swipeDebug { "FAST stop by=rest" }
        }
    }

    /** Tells the physics that the finger of [state] left the pad, once. */
    private fun liftFastScrollContact(state: IndirectTouchState, timestamp: Double) {
        if (state.fastScrollLifted) return
        state.fastScrollLifted = true
        fastScroll?.physics?.onTouchUp(timestamp)
        swipeDebug { "FAST touch up" }
    }

    /**
     * Hands focus back to the item at the anchor the physics ended at. The item may not be composed
     * yet, so the search is retried for a few frames; after that the mode ends with the container
     * still holding focus, which the next directional key moves into an item.
     */
    private fun attemptFastScrollExit(session: FastScrollSession) {
        val physics = session.physics
        val anchor = fastScrollExitAnchor(physics.hitEnd, session.lastRequested)
        session.exitAnchor = anchor
        val node = session.node
        val restored = scene.runRemoteInput { node.endFastScroll(anchor) }
        // The callbacks of the app ran: they can dispose this mediator underneath the call.
        if (fastScroll !== session) return
        if (restored) {
            finishFastScroll(session, restored = true)
        } else if (node.previousFocusIsOnContainer() || !session.exitRetries.tryConsume()) {
            // No item to focus and the focus came from the container itself: nothing will be placed
            // later, so skip the retries.
            if (restoreFastScrollPreviousFocus(session)) return
            if (fastScroll !== session) return
            node.abortFastScroll()
            finishFastScroll(session, restored = false)
        }
    }

    private fun restoreFastScrollPreviousFocus(session: FastScrollSession): Boolean {
        val restored = scene.runRemoteInput { session.node.restorePreviousFocusAndEnd() }
        if (fastScroll !== session) return true
        if (!restored) return false
        finishFastScroll(session, restored = true, previous = true)
        return true
    }

    /**
     * Ends the mode now, for a reason that is not the physics. With [restore] focus goes back to the
     * item at the centre if there is one, otherwise the container is only released.
     */
    private fun forceEndFastScroll(reason: String, restore: Boolean) {
        val session = fastScroll ?: return
        session.exitReason = reason
        var restored = false
        if (restore && session.node.isFastScrolling) {
            session.exitAnchor = RemoteFastScrollExitAnchor.Center
            restored =
                scene.runRemoteInput { session.node.endFastScroll(RemoteFastScrollExitAnchor.Center) }
            if (fastScroll !== session) return
            if (!restored && session.node.isFastScrolling) {
                restored = scene.runRemoteInput { session.node.restorePreviousFocusAndEnd() }
                if (fastScroll !== session) return
                if (restored) {
                    finishFastScroll(session, restored = true, previous = true)
                    return
                }
            }
        }
        if (!restored) session.node.abortFastScroll()
        finishFastScroll(session, restored)
    }

    private fun finishFastScroll(
        session: FastScrollSession,
        restored: Boolean,
        previous: Boolean = false,
    ) {
        if (fastScroll !== session) return
        fastScroll = null
        // The key that drove the mode may still be down: its release is not owed to the scene.
        session.holdKeyId?.let { swallowedFastScrollKeyIds.add(it) }
        touchOracle.endSampling(session.samplingToken)
        swipeChain.reset()
        swipeDebug {
            val duration = (CACurrentMediaTime() - session.startedAt) * 1000.0
            "FAST exit reason=${session.exitReason ?: "abort"} anchor=${session.exitAnchor} " +
                "hitEnd=${session.physics.hitEnd} v=${session.physics.velocity} duration=${duration}ms"
        }
        swipeDebug { "FAST focus restored=${if (previous) "previous" else restored}" }
    }

    private fun dispatchIndirectSwipe(state: IndirectTouchState, timestamp: Double) {
        val key = state.armedKey ?: return
        swipeDebug { "SWIPE dispatch ${directionName(key)} t=${debugSeconds(timestamp)}" }
        state.verdict = IndirectTouchVerdict.DISPATCHED
        state.dispatchedKey = key
        // This move is paid for; the finger pays for the next one a step further along its axis.
        state.travelSteps = 1
        state.dragNextTravel = state.maxTravel + DRAG_STEP_TRAVEL
        val swipes = swipeChain.onSwipeDispatched(key, state.beginTimestamp)
        state.followsMomentumSwipe = swipeChain.followsMomentumSwipe
        swipeDebug {
            val gap = swipeChain.currentGap
            "CHAIN swipes=$swipes moves=${swipeChain.focusMoves} " +
                "momentumMoves=${swipeChain.momentumMoves} momentumSwipes=${swipeChain.momentumSwipes} gap=${if (gap == Double.MAX_VALUE) "none" else "${gap * 1000.0}ms"}"
        }
        val rows = swipeRows(state)
        swipeChain.onSwipeRows(rows)
        val cancellations = momentumCancellations
        val consumed =
            onKeyboardEvent(KeyEvent(key, KeyEventType.KeyDown, nativeEvent = state.swipe))
        onKeyboardEvent(KeyEvent(key, KeyEventType.KeyUp, nativeEvent = state.swipe))
        if (cancellations != momentumCancellations) return
        if (consumed) swipeChain.onFocusMoved()
        // A swipe in the same direction takes over what the burst it replaces still owed.
        val replaced = momentumBurst
        val carried =
            if (replaced != null && replaced.key == key) {
                replaced.remaining.coerceAtMost(MOMENTUM_MAX_ROWS_CHAINED)
            } else {
                0
            }
        cancelMomentum("replaced")
        if (!consumed) return
        val startedAt = CACurrentMediaTime()
        lastBurstStepAt = startedAt
        momentumBurst =
            TvMomentumBurst(
                key = key,
                startedAt = startedAt,
                rows = rows,
                tau = if (state.followsMomentumSwipe) MOMENTUM_CHAIN_TAU_S else MOMENTUM_TAU_S,
                carried = carried,
                swipe = state.swipe,
            )
        if (carried > 0) swipeDebug { "MOMENTUM carried=$carried" }
        burstSource = state
        isSamplingFling = true
        flingSamplingToken = touchOracle.beginSampling()
    }

    /**
     * Tracks whether the finger of [state] is resting: as long as it stays within
     * [REST_ANCHOR_TOLERANCE] of its anchor for longer than [REST_RESET_DURATION_S], the origin and
     * the swipe timer move to where it rests, so a rest followed by a real movement still swipes
     * and a slow drift never accumulates into one.
     *
     * Returns `true` when the origin was just moved, i.e. when there is no displacement left to
     * evaluate for this sample.
     */
    private fun updateIndirectRest(
        state: IndirectTouchState,
        position: Offset,
        timestamp: Double,
    ): Boolean {
        val moved = hypot(position.x - state.restAnchor.x, position.y - state.restAnchor.y)
        if (moved >= REST_ANCHOR_TOLERANCE) {
            state.restAnchor = position
            state.lastSignificantMoveTime = timestamp
            return false
        }
        if (timestamp - state.lastSignificantMoveTime <= REST_RESET_DURATION_S) return false
        state.origin = position
        state.startTimestamp = timestamp
        state.lastSignificantMoveTime = timestamp
        if (state.usesOracle) {
            // The swipe now starts where the finger rests, so where it rests is what decides whether
            // this is a ring contact, and no centre crossing it made before the rest counts: a
            // finger parked on the ring and then dragged around it has to cross again.
            state.ringOrigin = isRingOrigin(position.x, position.y, touchOracle.hasRing)
            state.minRadius = hypot(position.x, position.y)
        }
        return true
    }

    /**
     * Moves the origin and the swipe timer of an oracle contact to [position], with the travel
     * measured from there. The ring verdict and the centre crossing are kept: a slow drag that
     * started on the ring does not become a plain contact by being slow.
     */
    private fun reanchorIndirectTouch(
        state: IndirectTouchState,
        position: Offset,
        timestamp: Double,
    ) {
        state.origin = position
        state.restAnchor = position
        state.startTimestamp = timestamp
        state.lastSignificantMoveTime = timestamp
        state.maxTravel = 0f
        state.peakSpeed = 0f
    }

    /**
     * `true` if a clickpad press is held, or if one began during the contact that started at
     * [startTimestamp] or shortly before it. Press and touch timestamps are hardware event times
     * rather than delivery times, so comparing them is immune to UIKit delivering the press and the
     * touch callbacks of one click out of order.
     */
    private fun isClickpadPressOverlapping(startTimestamp: Double, timestamp: Double): Boolean =
        touchOracle.anyButtonPressed() ||
            pressDispatchLog.isClickpadPressHeld(timestamp) ||
            pressDispatchLog.clickpadPressTimestamp >= startTimestamp - PRESS_SUPPRESSION_WINDOW_S

    /**
     * Converts [UITouch] objects from [touches] to [ComposeScenePointer] and dispatches them to the
     * appropriate handlers.
     *
     * @param touches a [Set] of [UITouch] objects. Erasure happens due to K/N not supporting Obj-C
     *   lightweight generics.
     * @param event the [UIEvent] associated with the touches
     * @param eventKind the [TouchesEventKind] of the touches
     */
    private fun onTouchesEvent(
        touches: Set<*>,
        event: UIEvent?,
        eventKind: TouchesEventKind,
    ): PointerEventResult {
        when (eventKind) {
            TouchesEventKind.BEGAN -> redrawer.ongoingInteractionEventsCount += touches.count()
            TouchesEventKind.ENDED -> redrawer.ongoingInteractionEventsCount -= touches.count()
            TouchesEventKind.MOVED -> {}
        }

        // Siri Remote swipes (UITouchTypeIndirect) are converted directly to directional key
        // events and are NOT forwarded to the Compose pointer input pipeline.
        val pointerTouches = mutableListOf<UITouch>()
        for (anyTouch in touches) {
            val touch = anyTouch as UITouch
            if (touch.type == UITouchTypeIndirect) {
                onIndirectTouchEvent(touch, eventKind)
            } else {
                pointerTouches.add(touch)
            }
        }

        if (pointerTouches.isEmpty()) {
            previousButtonMask = event.buttonMaskOrZero
            if (eventKind != TouchesEventKind.MOVED) previousTouchEventKind = eventKind
            return PointerEventResult(anyMovementConsumed = false)
        }

        val pointers =
            pointerTouches.mapIndexed { index, touch ->
                val position = touch.offsetInView(_backgroundView, screenDensity.density)
                val pointerType =
                    when (touch.type) {
                        UITouchTypeDirect -> PointerType.Touch
                        UITouchTypeIndirectPointer -> PointerType.Mouse
                        else -> PointerType.Touch
                    }
                val id =
                    touch.hashCode().toLong().takeIf { pointerType != PointerType.Mouse }
                        ?: index.toLong()
                ComposeScenePointer(
                    id = PointerId(id),
                    position = position,
                    pressed = touch.isPressed,
                    type = pointerType,
                    pressure = touch.force.toFloat(),
                    historical =
                        event?.historicalChangesForTouch(touch, _overlayView, screenDensity.density)
                            ?: emptyList(),
                )
            }

        // UIKit sends buttonMask that was before the release action. It should be empty if no
        // pressed pointers left.
        val pointerButtonsMask = event.buttonMaskOrZero.takeIf { pointers.any { it.pressed } } ?: 0L

        return scene
            .sendPointerEvent(
                eventType = eventKind.toPointerEventType(),
                pointers = pointers,
                timeMillis = event.timeMillis,
                nativeEvent = event,
                button = event?.getButton(previousButtonMask, eventKind, previousTouchEventKind),
                buttons = PointerButtons(pointerButtonsMask),
                keyboardModifiers = PointerKeyboardModifiers(event.modifierFlagsOrZero),
            )
            .also {
                previousButtonMask = event.buttonMaskOrZero
                if (eventKind != TouchesEventKind.MOVED) previousTouchEventKind = eventKind
            }
    }

    private var previousButtonMask: Long = 0L
    private var previousTouchEventKind: TouchesEventKind? = null

    private var lastFocusedRect: Rect? = null

    private fun getFocusedRect(): Rect? {
        return scene.focusManager.getFocusRect(afterLayout = false)?.also { lastFocusedRect = it }
            ?: lastFocusedRect
    }

    var onOutsidePointerEvent: (PointerEventType) -> Unit by _overlayView::onOutsidePointerEvent
    var isInterceptingOutsideEvents: Boolean by _overlayView::isInterceptingOutsideEvents
    var interactionBounds = IntRect.Zero

    fun setContent(content: @Composable () -> Unit) {
        _backgroundView.runOnceOnAppeared {
            scene.setContent {
                ProvideComposeSceneMediatorCompositionLocals {
                    interopContainer.TrackInteropPlacementContainer(content = content)
                }
            }
        }
    }

    private var isLayoutTransitionAnimating = false

    fun prepareAndGetSizeTransitionAnimation(
        withProgress: suspend ((Float) -> Unit) -> Unit
    ): suspend () -> Unit {
        isLayoutTransitionAnimating = true

        val initialWindowInsets = windowInsetsManager.windowInsetsSnapshot()
        val initialSize = scene.size?.toSize() ?: return {}

        return {
            try {
                withProgress { progress ->
                    windowInsetsManager.updateInsetsForAnimation(
                        initialWindowInsets = initialWindowInsets,
                        progress = progress,
                    )
                    composeSceneSize =
                        lerp(start = initialSize, stop = currentViewSize, fraction = progress)
                            .roundToIntSize()
                }
            } finally {
                isLayoutTransitionAnimating = false
                updateLayout()
            }
        }
    }

    private var initialFocusPending = true

    fun render(canvas: Canvas, nanoTime: Long) {
        withFrameGuard {
            with(sceneRenderingScope) { scene.render(frameRecomposer, canvas, nanoTime) }
        }
        // The 1.12 scene measures and lays out inside render(), so this is the first point where
        // layout has placed the focus targets.
        assignInitialFocusIfNeeded()
    }

    private fun assignInitialFocusIfNeeded() {
        // UIKit does not focus the Compose input views on tvOS. Enter the Compose focus tree
        // once layout has placed its targets, without replacing an application's focus request.
        // Keep this pending for screens that initially have no focusable content, but stop after
        // focus is established so a later clearFocus() is not undone by another layout pass.
        if (initialFocusPending && isFocusEnabled && _overlayView.window != null) {
            if (scene.focusManager.hasFocus || scene.focusManager.takeFocus(FocusDirection.Enter)) {
                initialFocusPending = false
            }
        }
    }

    private var isPerformingFrame = false

    private inline fun withFrameGuard(crossinline block: () -> Unit) {
        if (isPerformingFrame) {
            // Fixes issue with reentrant redraws from native text-input edits mid-frame
            return
        }
        isPerformingFrame = true
        try {
            block()
        } finally {
            isPerformingFrame = false
        }
    }

    fun retrieveInteropTransaction(): UIKitInteropTransaction =
        interopContainer.retrieveTransaction()

    @OptIn(InternalComposeUiApi::class)
    @Composable
    private fun ProvideComposeSceneMediatorCompositionLocals(content: @Composable () -> Unit) =
        CompositionLocalProvider(
            LocalInteropContainer provides interopContainer,
            LocalUIView provides _overlayView,
            content = content,
        )

    private fun dispose() {
        touchOracle.removeSampleListener(oracleSampleListener)
        // The scene is about to close, so the container is released without any focus work.
        forceEndFastScroll("dispose", restore = false)
        swallowedFastScrollKeyIds.clear()
        cancelMomentum("dispose")
        swipeChain.reset()
        if (isSamplingOracle) {
            isSamplingOracle = false
            touchOracle.endSampling(oracleSamplingToken)
        }
        indirectTouches.clear()
        repeatingKeys.values.forEach { it.cancel() }
        repeatingKeys.clear()
        swallowedSelectKeyId = null
        focusedViewsList?.remove(_overlayView)

        onPreviewKeyEvent = { false }
        onKeyEvent = { false }

        _overlayView.dispose()
        _backgroundView.dispose()

        _overlayView.removeFromSuperview()
        _backgroundView.removeFromSuperview()

        scene.close()
        frameRecomposer.close()
        interopContainer.dispose()
        semanticsOwnerListener.dispose()
    }

    /** Updates the [ComposeScene] with the properties derived from the [_overlayView]. */
    private fun updateLayout() {
        if (isLayoutTransitionAnimating) {
            return
        }
        windowInsetsManager.updateInsets()
        composeSceneSize = currentViewSize.roundToIntSize()
        interactionBounds =
            with(screenDensity) { _overlayView.bounds.toDpRect().toRect().roundToIntRect() }
    }

    private val currentViewSize: Size
        get() {
            return with(screenDensity) {
                _overlayView.frame.useContents { size.toDpSize() }.toSize()
            }
        }

    fun sceneDidAppear() {
        focusedViewsList?.addAndFocus(_overlayView)
        redrawer.setNeedsRedraw()
    }

    fun sceneWillDisappear() {
        // No keyboard manager to stop on tvOS
        forceEndFastScroll("disappear", restore = false)
        cancelMomentum("disappear")
    }

    fun didUpdateFocusInContext() {
        if (!scene.focusManager.hasFocus) {
            scene.focusManager.takeFocus(FocusDirection.Enter)
        }
    }

    // The overlay view needs to be the first responder to handle keyboard/Siri Remote key events.
    // The system generally reassigns first-responder focus to the overlay when other views resign
    // it, except at the time of initial appearance, so claim it explicitly once attached.
    private fun focusOverlayViewIfNeeded() {
        if (!isFocusEnabled) {
            return
        }
        val window = _overlayView.window ?: return
        fun findFirstResponder(view: UIView): UIView? {
            if (view.isFirstResponder) {
                return view
            }
            for (subview in view.subviews) {
                subview as UIView
                val firstResponder = findFirstResponder(subview)
                if (firstResponder != null) {
                    return firstResponder
                }
            }
            return null
        }
        if (findFirstResponder(window) == null) {
            _overlayView.becomeFirstResponder()
        }
    }

    fun setKeyEventListener(
        onPreviewKeyEvent: ((KeyEvent) -> Boolean)?,
        onKeyEvent: ((KeyEvent) -> Boolean)?,
    ) {
        this.onPreviewKeyEvent = onPreviewKeyEvent ?: { false }
        this.onKeyEvent = onKeyEvent ?: { false }
    }

    /**
     * Converts [UIPress] objects to [KeyEvent] and dispatches them to the appropriate handlers.
     * Handles key repeat by starting a coroutine that repeatedly dispatches KeyDown while a key is
     * held, similar to Android's key repeat behavior. Every press is dispatched to Compose as a
     * KeyDown on Began and as a KeyUp on Ended, whether or not the KeyDown was consumed, so
     * handlers that act on [KeyEventType.KeyUp] (back navigation, overlays) always see their KeyUp.
     * A Cancelled press dispatches nothing: the system claimed it, it is not a release.
     *
     * Only Menu can reach the system, and only when Compose consumed neither of its phases (the app
     * is at its root screen). The real Began is forwarded as soon as the KeyDown comes back
     * unconsumed, because UIKit arms its Home gesture on a live Began and ignores a replayed one.
     * The KeyUp is still offered to Compose: unconsumed, the press is completed with its real
     * Ended; consumed, the press is cancelled instead, which disarms the gesture and keeps the app
     * running.
     *
     * @param presses a [Set] of [UIPress] objects. Erasure happens due to K/N not supporting Obj-C
     *   lightweight generics.
     * @param pressesEvent the event the presses belong to, used to recognize presses that the
     *   responder chain delivers to this mediator twice.
     */
    fun onKeyboardPresses(presses: Set<*>, pressesEvent: UIPressesEvent?): TvPressForwarding {
        // A real press outranks the momentum of the swipe before it.
        cancelMomentum("press")
        swipeChain.reset()
        var passThrough: MutableSet<Any?>? = null
        var forwardingMode = TvPressForwardingMode.AsIs
        val forwardedKeyIds = mutableListOf<Long>()
        fun passToSystem(press: Any?) {
            val set = passThrough ?: mutableSetOf<Any?>().also { passThrough = it }
            set.add(press)
        }
        fun forwardToSystem(
            press: Any?,
            keyId: Long,
            mode: TvPressForwardingMode,
            debugPhase: String,
        ) {
            swipeDebug { "MENU forward mode=$debugPhase keyId=$keyId" }
            forwardingMode = mode
            pressDispatchLog.markForwardedToSystem(pressesEvent, keyId)
            passToSystem(press)
            forwardedKeyIds.add(keyId)
        }

        presses.forEach { anyPress ->
            val press = anyPress as UIPress
            val rawEvent = press.toComposeEvent()
            // Siri Remote's Menu button is tvOS's back gesture.
            val isMenu = rawEvent.key == Key.Menu
            val event = if (isMenu) rawEvent.copy(key = Key.Back) else rawEvent
            val keyId = press.key?.keyCode?.toLong() ?: -(press.type.toLong() + 1L)
            val phase = press.phase

            if (pressDispatchLog.isForwardedToSystem(pressesEvent, keyId)) {
                // An unhandled Menu press, travelling up the
                // responder chain: keep it moving towards UIApplication without dispatching it
                // to the scene again.
                passToSystem(anyPress)
                return@forEach
            }

            if (phase == UIPressPhase.UIPressPhaseChanged) {
                // An analog button reporting its pressure: Compose has no event for it, and it
                // must not be recorded in the dispatch log — that would overwrite the phase
                // remembered for the press that is still down.
                return@forEach
            }

            // The Siri Remote clickpad doubles as its buttons (Select in the middle, arrows
            // on the outer ring): clicking it produces a press alongside an indirect touch that
            // drifts enough to look like a swipe. The press is recorded before the echo check,
            // so the mediator that sees the echo rather than the original still latches it.
            if (
                when (event.key) {
                    Key.DirectionCenter,
                    Key.DirectionUp,
                    Key.DirectionDown,
                    Key.DirectionLeft,
                    Key.DirectionRight -> true
                    else -> false
                }
            ) {
                pressDispatchLog.recordClickpadPress(keyId, phase, press.timestamp)
            }

            if (!pressDispatchLog.shouldEvaluate(pressesEvent, keyId, phase)) {
                // Already evaluated by another mediator for this event; do not dispatch twice.
                return@forEach
            }

            when (phase) {
                UIPressPhase.UIPressPhaseBegan -> {
                    // A key swallowed by a mode that ended since is not owed a release any more.
                    swallowedFastScrollKeyIds.remove(keyId)
                    fastScroll?.let { session ->
                        // Any press that starts while fast scrolling runs is a new one: the held
                        // key, which drives the mode, only ever repeats.
                        session.physics.stop()
                        session.holdKeyId?.let { held ->
                            // The key that drove the mode is still down: it no longer drives, and
                            // its release is swallowed.
                            session.holdKeyId = null
                            if (held != keyId) swallowedFastScrollKeyIds.add(held)
                        }
                        swipeDebug { "FAST stop by=press key=${event.key}" }
                        if (isMenu) {
                            // Back is delivered below as usual, to the item that has focus again.
                            forceEndFastScroll("menu", restore = true)
                        } else if (isSwallowedInFastScroll(event.key)) {
                            // The container holds focus: an arrow would make the default handling
                            // enter a child, and Select would click nothing.
                            swallowedFastScrollKeyIds.add(keyId)
                            return@forEach
                        }
                    }
                    // A new Began for this press id means the previous press is over even if
                    // its Ended never arrived (the system keyboard overlay can absorb it), so
                    // clear here what the Ended phase would have cleared.
                    if (swallowedSelectKeyId == keyId) {
                        swallowedSelectKeyId = null
                        repeatingKeys.remove(keyId)?.cancel()
                    }
                    pressDispatchLog.takePendingMenu(keyId)
                    val downConsumed = onKeyboardEvent(event, keyId)
                    if (!downConsumed && isMenu) {
                        // Forward the real Began now. UIKit arms its Home gesture on a Began it
                        // receives while the press is live, and ignores one replayed from the
                        // later pressesEnded call, so holding this phase back would leave the
                        // press unknown to the system. The press stays pending: a KeyUp Compose
                        // claims cancels it before UIKit can act on the release.
                        pressDispatchLog.setPendingMenu(keyId)
                        forwardToSystem(anyPress, keyId, TvPressForwardingMode.AsIs, "began")
                    }

                    // Key repeat runs whether or not the KeyDown was consumed: a directional key
                    // is unconsumed at a focus boundary and while `isKeyRepeat` long-press
                    // handlers return false on the first KeyDown, and both must keep repeating.
                    // Menu is excluded — repeating it would re-dispatch Back every interval —
                    // and so is a KeyDown swallowed to open the system keyboard, which never
                    // reached the scene.
                    if (
                        !isMenu &&
                            swallowedSelectKeyId != keyId &&
                            repeatingKeys[keyId]?.isActive != true
                    ) {
                        val repeatEvent = event.copy(isRepeat = true)
                        // Only a directional key repeats on the native cadence and can end in fast
                        // scrolling, which replaces the repeat that would be its eleventh move.
                        val isDirectional = isDirectionalKey(event.key)
                        val hold = TvHoldRepeatTracker(isDirectional)
                        hold.onInitialPress(downConsumed)
                        repeatingKeys[keyId] =
                            CoroutineScope(coroutineContext).launch {
                                while (isActive) {
                                    delay(hold.nextDelayMs())
                                    // Fast scrolling replaces the repeats of every other key too.
                                    if (fastScroll != null) continue
                                    if (
                                        hold.shouldTryFastEntry() &&
                                            tryEnterFastScrollFromHold(event.key, keyId, hold.moves)
                                    ) {
                                        break
                                    }
                                    val consumed = onKeyboardEvent(repeatEvent, keyId)
                                    if (isDirectional) {
                                        swipeDebug {
                                            "KEYREPEAT tick=${hold.repeatIndex + 1} " +
                                                "key=${directionName(event.key)} " +
                                                "consumed=$consumed moves=${hold.moves}"
                                        }
                                    }
                                    hold.onRepeatDispatched(consumed)
                                }
                            }
                    }
                }
                UIPressPhase.UIPressPhaseEnded,
                UIPressPhase.UIPressPhaseCancelled -> {
                    repeatingKeys.remove(keyId)?.cancel()
                    fastScroll?.let { session ->
                        if (session.holdKeyId == keyId) {
                            // The held key drove the mode: releasing it brakes the scroll.
                            session.holdKeyId = null
                            session.physics.onHoldReleased(CACurrentMediaTime())
                            swallowedFastScrollKeyIds.add(keyId)
                            swipeDebug { "FAST hold released" }
                        }
                    }
                    if (swallowedFastScrollKeyIds.remove(keyId)) {
                        // The scene never saw its KeyDown while the container held focus, or must
                        // not see a KeyUp for the key that drove the mode.
                        return@forEach
                    }
                    val isEnded = phase == UIPressPhase.UIPressPhaseEnded
                    // Two-way policy:
                    //  - Ended: the KeyUp is dispatched whatever happened on the KeyDown, so a
                    //    handler that only acts on KeyUp still gets it.
                    //  - Cancelled: nothing is dispatched. The system took the press away, it is
                    //    not a release, and a KeyUp would make `clickable()` fire onClick and a
                    //    KeyUp-gated Back handler pop a screen. The key left behind in
                    //    FocusOwnerImpl.keysCurrentlyDown and the interaction left in
                    //    `currentKeyPressInteractions` heal by themselves: validateKeyEvent never
                    //    rejects a KeyDown, so the next press of that key is dispatched normally
                    //    and its KeyUp removes both entries.
                    val consumed = isEnded && onKeyboardEvent(event, keyId)
                    val wasPendingMenu = pressDispatchLog.takePendingMenu(keyId)
                    if (wasPendingMenu) {
                        if (consumed) {
                            // Compose claimed the KeyUp of a press whose Began already armed
                            // UIKit's Home gesture. Forwarding the Ended would complete that
                            // gesture and exit the app, and swallowing it would leave UIKit
                            // holding a press for good, so the press is cancelled from inside
                            // this live pressesEnded call: UIKit sees Began then Cancelled and
                            // acts on neither.
                            forwardToSystem(
                                anyPress,
                                keyId,
                                TvPressForwardingMode.Cancelled,
                                "cancelled",
                            )
                        } else {
                            // Nothing in Compose wanted the press: complete the forwarded Began
                            // with the phase this one arrived in, so UIKit returns Home on a
                            // release and drops the press on a real cancellation. It is the real
                            // UIPress instance, never a synthesised one.
                            forwardToSystem(
                                anyPress,
                                keyId,
                                TvPressForwardingMode.AsIs,
                                if (isEnded) "ended" else "cancelled",
                            )
                        }
                    }
                }
                else -> Unit
            }
        }

        if (passThrough == null) return TvPressForwarding.None
        return TvPressForwarding(
            passThrough = passThrough.orEmpty(),
            mode = forwardingMode,
            onForwardingFinished = {
                forwardedKeyIds.forEach(pressDispatchLog::unmarkForwardedToSystem)
            },
        )
    }

    private fun onKeyboardEvent(keyEvent: KeyEvent, keyId: Long? = null): Boolean {
        // Show the tvOS system keyboard when the user presses Select on a focused text field.
        // The keyboard must not open on D-pad navigation alone (only on explicit Select press).
        // The matching KeyUp is swallowed too: the scene never saw the KeyDown, so
        // FocusOwnerImpl.validateKeyEvent would drop the KeyUp anyway, and `clickable()`
        // would fire onClick for a click the user never made on the field.
        // (The alternative is to send the KeyDown to the scene first and only open the
        // keyboard if nothing consumed it; that changes more behaviour, so it isn't done.)
        if (keyEvent.key == Key.DirectionCenter) {
            if (
                keyEvent.type == KeyEventType.KeyUp &&
                    keyId != null &&
                    keyId == swallowedSelectKeyId
            ) {
                swallowedSelectKeyId = null
                return true
            }
            if (
                keyEvent.type == KeyEventType.KeyDown &&
                    keyId != null &&
                    keyId == swallowedSelectKeyId
            ) {
                // A repeat of the KeyDown that was already swallowed to open the keyboard.
                return true
            }
            if (
                tvOSTextInputService.activeRequest != null &&
                    !tvOSTextInputService.isKeyboardVisible &&
                    keyEvent.type == KeyEventType.KeyDown
            ) {
                swallowedSelectKeyId = keyId
                tvOSTextInputService.showKeyboard()
                return true
            }
        }

        // UIKit owns input while its keyboard overlay is visible. Returning unconsumed
        // keeps physical presses in the native responder chain and drops synthetic swipe
        // events instead of delivering them to the Compose field behind the keyboard.
        // Keep this after the paired Select handling above: the Select that opened the
        // keyboard must still have its matching KeyUp swallowed.
        if (tvOSTextInputService.isKeyboardVisible) return false

        // tvOS text fields must not consume D-pad presses — there's no in-line cursor on
        // tvOS, so consuming the key would trap focus inside the field. While a Compose
        // text field holds input focus, route D-pad directly to focus traversal instead
        // of letting `scene.sendKeyEvent` deliver it to the field.
        if (tvOSTextInputService.activeRequest != null && !tvOSTextInputService.isKeyboardVisible) {
            val direction = keyEvent.toFocusDirection()
            if (direction != null) {
                if (onPreviewKeyEvent(keyEvent)) return true
                if (keyEvent.type == KeyEventType.KeyDown) {
                    scene.focusManager.moveFocus(direction)
                }
                return true
            }
        }

        return onPreviewKeyEvent(keyEvent) ||
            scene.sendKeyEvent(keyEvent) ||
            onKeyEvent(keyEvent) ||
            navigationEventInput.onKeyEvent(keyEvent)
    }

    private fun KeyEvent.toFocusDirection(): FocusDirection? =
        when (key) {
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            Key.DirectionLeft -> FocusDirection.Left
            Key.DirectionRight -> FocusDirection.Right
            else -> null
        }

    private inner class PlatformContextImpl : PlatformContext {
        override val windowInfo: WindowInfo
            get() = windowContext.windowInfo

        override val architectureComponentsOwner
            get() = this@ComposeSceneMediator.architectureComponentsOwner

        override val screenReader: PlatformScreenReader
            get() = platformScreenReader

        override val hapticFeedback: HapticFeedback by
            lazy(LazyThreadSafetyMode.NONE) { TvOSHapticFeedback() }

        override fun convertLocalToWindowPosition(localPosition: Offset): Offset =
            windowContext.convertLocalToWindowPosition(_overlayView, localPosition)

        override fun convertWindowToLocalPosition(positionInWindow: Offset): Offset =
            windowContext.convertWindowToLocalPosition(_overlayView, positionInWindow)

        override fun convertLocalToScreenPosition(localPosition: Offset): Offset =
            windowContext.convertLocalToScreenPosition(_overlayView, localPosition)

        override fun convertScreenToLocalPosition(positionOnScreen: Offset): Offset =
            windowContext.convertScreenToLocalPosition(_overlayView, positionOnScreen)

        override val viewConfiguration
            get() = this@ComposeSceneMediator.viewConfiguration

        // tvOS has no touch surface: the Siri Remote is a directional input device. Starting in
        // InputMode.Touch would make Focusability.SystemDefined resolve to "cannot focus", so
        // requestFocus() and moveFocus() would be no-ops until the first remote key event.
        override val inputModeManager by
            lazy(LazyThreadSafetyMode.NONE) { DefaultInputModeManager(InputMode.Keyboard) }
        override val semanticsOwnerListener
            get() = this@ComposeSceneMediator.semanticsOwnerListener

        override val windowInsets
            get() = this@ComposeSceneMediator.windowInsetsManager.windowInsets

        override val outOfFrameExecutor
            get() = this@ComposeSceneMediator.redrawer.outOfFrameExecutor

        // On tvOS, Siri Remote touches are UITouchTypeIndirect which map to PointerType.Mouse.
        // Clearing focus on mouse-down would lose Compose focus on every remote swipe.
        override val isClearFocusOnMouseDownEnabled: Boolean
            get() = false

        override var isKeepScreenOnEnabled: Boolean
            get() = UIKitIdleTimerManager.isIdleTimerDisabled
            set(value) {
                UIKitIdleTimerManager.setIdleTimerState(this@ComposeSceneMediator, value)
            }

        override fun voteFrameRate(frameRate: Float, frameRateCategory: Float) {
            redrawer.voteFrameRate(frameRate, frameRateCategory)
        }

        /**
         * Makes `LocalSoftwareKeyboardController.show()/hide()` functional on tvOS by forwarding to
         * the tvOS keyboard overlay. The controller only forwards while a text session is active on
         * the focused field, so `show()` is a no-op when no Compose text field requested input;
         * [TvOSTextInputService.showKeyboard] guards that case too. Text state itself flows through
         * [startInputMethod], hence the no-op session methods.
         */
        @Suppress("DEPRECATION")
        override val textInputService: androidx.compose.ui.text.input.PlatformTextInputService =
            object : androidx.compose.ui.text.input.PlatformTextInputService {
                override fun startInput(
                    value: TextFieldValue,
                    imeOptions: ImeOptions,
                    onEditCommand: (List<EditCommand>) -> Unit,
                    onImeActionPerformed: (ImeAction) -> Unit,
                ) = Unit

                override fun stopInput() = Unit

                override fun showSoftwareKeyboard() {
                    tvOSTextInputService.showKeyboard()
                }

                override fun hideSoftwareKeyboard() {
                    tvOSTextInputService.hideKeyboard()
                }

                override fun updateState(oldValue: TextFieldValue?, newValue: TextFieldValue) = Unit
            }

        override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
            val sessionId = tvOSTextInputService.startInput(request)
            try {
                // Suspend until Compose cancels this session (text field loses focus).
                // The keyboard UI is shown/hidden independently via showKeyboard()/hideKeyboard();
                // we do NOT cancel here on keyboard dismissal so the user can re-open the
                // keyboard by pressing Select on the same field without losing and regaining focus.
                kotlinx.coroutines.suspendCancellableCoroutine<Nothing> { continuation ->
                    continuation.invokeOnCancellation { tvOSTextInputService.stopInput(sessionId) }
                }
            } finally {
                tvOSTextInputService.stopInput(sessionId)
            }
        }
    }
}

private fun UIEvent.getButton(
    previousButtonMask: Long,
    eventKind: TouchesEventKind,
    previousEventKind: TouchesEventKind?,
): PointerButton? =
    if (eventKind == TouchesEventKind.MOVED) {
        null
    } else if (
        buttonMaskOrZero and UIEventButtonMaskPrimary != 0L &&
            (previousButtonMask and UIEventButtonMaskPrimary == 0L ||
                eventKind != previousEventKind)
    ) {
        PointerButton.Primary
    } else if (
        buttonMaskOrZero and UIEventButtonMaskSecondary != 0L &&
            (previousButtonMask and UIEventButtonMaskSecondary == 0L ||
                eventKind != previousEventKind)
    ) {
        PointerButton.Secondary
    } else {
        null
    }

private val UIEvent?.timeMillis: Long
    get() {
        // If the touches were cancelled due to gesture failure, the timestamp is not available,
        // because no actual event with touch updates happened. We just use the current time in
        // this case.
        val timestamp = this?.timestamp ?: CACurrentMediaTime()
        return (timestamp * 1e3).toLong()
    }

private fun TouchesEventKind.toPointerEventType(): PointerEventType =
    when (this) {
        TouchesEventKind.BEGAN -> PointerEventType.Press
        TouchesEventKind.MOVED -> PointerEventType.Move
        TouchesEventKind.ENDED -> PointerEventType.Release
    }

private fun UIEvent.historicalChangesForTouch(
    touch: UITouch,
    view: UIView,
    density: Float,
): List<HistoricalChange> {
    val touches = coalescedTouchesForTouch(touch) ?: return emptyList()

    return if (touches.size > 1) {
        // the last touch is not included because it is the actual touch reported by the event
        touches.dropLast(1).map {
            val historicalTouch = it as UITouch
            val position = historicalTouch.offsetInView(view, density)
            HistoricalChange(
                uptimeMillis = (historicalTouch.timestamp * 1e3).toLong(),
                position = position,
                originalEventPosition = position,
                scaleFactor = 1f,
                panOffset = Offset.Zero,
            )
        }
    } else {
        emptyList()
    }
}

private val UIEvent?.buttonMaskOrZero: Long
    get() = 0L

private val UIEvent?.modifierFlagsOrZero: Long
    get() = this?.modifierFlags ?: 0L

private val UITouch.isPressed
    get() =
        when (phase) {
            UITouchPhase.UITouchPhaseEnded,
            UITouchPhase.UITouchPhaseCancelled -> false
            else -> true
        }

private fun UITouch.offsetInView(view: UIView, density: Float): Offset =
    locationInView(view).useContents { Offset(x.toFloat() * density, y.toFloat() * density) }
