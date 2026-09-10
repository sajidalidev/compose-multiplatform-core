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

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
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
import androidx.compose.ui.navigationevent.TvBackNavigationEventInput
import androidx.compose.ui.platform.AccessibilityMediator
import androidx.compose.ui.platform.ApplicationIdleTimer
import androidx.compose.ui.platform.CUPERTINO_TOUCH_SLOP
import androidx.compose.ui.platform.DefaultInputModeManager
import androidx.compose.ui.platform.DelegateRootForTestListener
import androidx.compose.ui.platform.FrameChoreographer
import androidx.compose.ui.platform.PlatformArchitectureComponentsOwner
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformScreenReader
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.TaskDispatchers
import androidx.compose.ui.platform.TvOSTextInputService
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.WindowContext
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.WindowInsetsManager
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.uikit.InterfaceOrientation
import androidx.compose.ui.uikit.LocalUIView
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.uikit.density
import androidx.compose.ui.unit.Constraints
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
import androidx.compose.ui.viewinterop.InteropSyncTransaction
import androidx.compose.ui.viewinterop.IosInteropContainer
import androidx.compose.ui.viewinterop.LocalInteropContainer
import androidx.compose.ui.viewinterop.TrackInteropPlacementContainer
import androidx.compose.ui.window.FocusedViewsList
import androidx.compose.ui.window.IosPrefetchScheduler
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.cinterop.CValue
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRectIsEmpty
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTimeInterval
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
// cross 0.35 within 60 to 151 ms; a resting finger, a ring rest and the drift inside a ring click
// travel at most 0.25 and need 179 to 406 ms to get there.
//
// Radius, in the absolute clickpad space reported by SiriRemoteTouchOracle (|x| and |y| in
// [0, 1]), beyond which a contact started on the outer ring of arrow buttons rather than on the
// centre pad. Native tvOS only swipes for movement that starts on the centre pad. Ring contacts
// between 0.60 and 0.75 are not gated here but by the press latch and by the distance gate,
// since they travel at most 0.25.
private const val CENTER_PAD_RADIUS = 0.75f
// A contact that lasts longer than this is a rest or a drag, not a swipe: genuine swipes cross
// the distance gate within 151 ms, while the contact roll of a centre click that lands partly on
// the ring takes 300 ms or more to cover the same distance.
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
// A contact that ends while armed is dispatched at once: a lifted finger can no longer click.
private const val DISPATCH_HOLD_S = 0.08
// A contact live for longer than this lost its terminal event: tvOS can absorb a touch's
// terminal event, e.g. when the keyboard overlay appears; without this the display link would
// poll forever.
private const val INDIRECT_CONTACT_MAX_AGE_S = 2.0
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
 * A contact produces at most one directional key: it starts as a [CANDIDATE] (or as [IGNORED] when
 * it started on the ring), becomes [ARMED] with a direction once it travelled far enough, and
 * leaves that state for good once the swipe is [DISPATCHED] or a clickpad press or an overlong
 * contact makes it [CANCELLED].
 *
 * A contact whose BEGAN arrives before the oracle reported any position of that contact starts as
 * [PENDING]: the pad reads exactly (0, 0) between contacts, so there is nothing to compare the
 * origin against yet. The first later sample decides the origin and the verdict.
 */
private enum class IndirectTouchVerdict {
    PENDING,
    CANDIDATE,
    ARMED,
    IGNORED,
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
    /** Position the rest detection measures against, and when it was last left. */
    var restAnchor: Offset = origin
    var lastSignificantMoveTime: Double = startTimestamp

    /** The direction the contact armed, and when it armed it. */
    var armedKey: Key? = null
    var armedAt: Double = 0.0
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
        // Compose gets every press in both phases; the only press that can reach tvOS is a Menu
        // press no Compose handler wanted on either phase, and it is replayed from its Ended
        // phase (see [replayToSystem]), never forwarded from here.
        val forwarding = onKeyboardPresses(presses, withEvent)
        if (forwarding.passThrough.isNotEmpty()) {
            super.pressesBegan(forwarding.passThrough, withEvent)
        }
    }

    override fun pressesEnded(presses: Set<*>, withEvent: UIPressesEvent?) {
        val forwarding = onKeyboardPresses(presses, withEvent)
        if (forwarding.passThrough.isNotEmpty()) {
            super.pressesEnded(forwarding.passThrough, withEvent)
        }
        replayToSystem(forwarding, withEvent)
    }

    override fun pressesCancelled(presses: Set<*>, withEvent: UIPressesEvent?) {
        val forwarding = onKeyboardPresses(presses, withEvent)
        if (forwarding.passThrough.isNotEmpty()) {
            super.pressesCancelled(forwarding.passThrough, withEvent)
        }
    }

    override fun pressesChanged(presses: Set<*>, withEvent: UIPressesEvent?) {
        // Analog buttons of an MFi controller report their pressure through this phase. Compose
        // has no event for it, so it is swallowed rather than left to UIResponder's default
        // implementation, which would send it up the chain behind Compose's back.
        val forwarding = onKeyboardPresses(presses, withEvent)
        if (forwarding.passThrough.isNotEmpty()) {
            super.pressesChanged(forwarding.passThrough, withEvent)
        }
    }

    /**
     * Sends [TvPressForwarding.replay] up the responder chain as a Began immediately followed by an
     * Ended, for a press that was held back while Compose was given a chance to handle its KeyUp.
     *
     * Assumption to verify on a simulator: UIKit acts on the *completed* press, so both phases have
     * to be replayed for the system to move the app to the background on Menu. The replayed
     * [platform.UIKit.UIPress] still carries `phase == Ended`; if UIKit keys on `press.phase`
     * rather than on the selector, the Began leg is a no-op and forwarding only the real Ended is
     * the fallback.
     */
    private fun replayToSystem(forwarding: TvPressForwarding, event: UIPressesEvent?) {
        if (forwarding.replay.isEmpty()) return
        for (press in forwarding.replay) {
            val single = setOf(press)
            super.pressesBegan(single, event)
            super.pressesEnded(single, event)
        }
        forwarding.onReplayFinished()
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
    private val frameChoreographer: FrameChoreographer,
    private val onFocusBehavior: OnFocusBehavior,
    private val isClearFocusOnMouseDownEnabled: Boolean,
    private val focusedViewsList: FocusedViewsList?,
    private val windowContext: WindowContext,
    private val architectureComponentsOwner: PlatformArchitectureComponentsOwner,
    val coroutineContext: CoroutineContext,
    private val navigationEventInput: TvBackNavigationEventInput,
    private val pressDispatchLog: TvPressDispatchLog,
    private val touchOracle: SiriRemoteTouchOracle,
    interfaceOrientationState: State<InterfaceOrientation>,
    composeSceneFactory: (platformContext: PlatformContext) -> ComposeScene,
    private val schedulePendingInteropViewUpdates: () -> Unit = {},
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
    private val keyRepeatInitialDelayMs = 500L
    private val keyRepeatIntervalMs = 50L
    private val platformScreenReader =
        object : PlatformScreenReader {
            override var isActive by mutableStateOf(false)
        }
    private val activitiesHandler = frameChoreographer.createActivitiesHandler()

    private val isActive
        get() = coroutineContext.isActive

    private var isPrefetchVoteActive: Boolean =
        false // TODO CMP-10587: Move inside the IosPrefetchScheduler
    private val prefetchScheduler =
        IosPrefetchScheduler(
            onHasWorkScheduled = { hasWork ->
                if (hasWork != isPrefetchVoteActive) {
                    isPrefetchVoteActive = hasWork
                    if (hasWork) {
                        activitiesHandler.onActivitiesStarted()
                    } else {
                        activitiesHandler.onActivitiesEnded()
                    }
                }
            }
        )

    /**
     * Indicates that a draw happened in the current display-link interval so
     * [FrameChoreographer.Listener.onOutOfFrame] can determine whether pending interop view updates
     * still need a host draw, and the prefetch scheduler can tell whether the draw loop was idle.
     */
    private var didDrawSinceDisplayLink = false
    private val frameChoreographerListener =
        object : FrameChoreographer.Listener {
            override fun onDisplayLinkTick() {
                didDrawSinceDisplayLink = false
            }

            override fun onOutOfFrame(
                lastFrameTimestamp: NSTimeInterval,
                targetTimestamp: NSTimeInterval,
            ) {
                if (!didDrawSinceDisplayLink && interopContainer.hasPendingViewUpdatesOnly) {
                    schedulePendingInteropViewUpdates()
                }

                prefetchScheduler.execute(
                    lastFrameTimestamp = lastFrameTimestamp,
                    targetTimestamp = targetTimestamp,
                    didDraw = didDrawSinceDisplayLink,
                )
            }
        }

    private val viewConfiguration: ViewConfiguration =
        object : ViewConfiguration by PlatformContext.DefaultViewConfiguration {
            override val touchSlop: Float
                get() = with(screenDensity) { CUPERTINO_TOUCH_SLOP.dp.toPx() }
        }

    private val scene: ComposeScene by lazy {
        composeSceneFactory(IosPlatformContext()).also {
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
    val composeSceneDensity: Density
        get() = scene.density

    fun setComposeSceneFontScale(fontScale: Float) {
        if (isActive) {
            scene.density = Density(composeSceneDensity.density, fontScale)
        }
    }

    @VisibleForTesting
    fun setComposeSceneDensity(density: Density) {
        if (isActive) {
            scene.density = density
        }
    }

    /**
     * Density of the hosting UIKit screen.
     *
     * This value is intentionally separate from [composeSceneDensity] so we can support setting
     * [composeSceneDensity] without regressions.
     */
    private val screenDensity: Density
        get() = windowContext.screenDensity

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
        IosInteropContainer(
            overlayContainer = _overlayView,
            backgroundContainer = _backgroundView,
            requestRedraw = frameChoreographer::requestFrame,
        )

    private val windowInsetsManager =
        WindowInsetsManager(
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

    @OptIn(InternalComposeUiApi::class)
    var rootForTestListener: PlatformContext.RootForTestListener? by DelegateRootForTestListener()

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
        get() {
            return scene.hasInvalidations() ||
                frameChoreographer.frameRecomposer.hasPendingWork() ||
                isLayoutTransitionAnimating ||
                semanticsOwnerListener.hasInvalidations
        }

    init {
        coroutineContext.job.invokeOnCompletion { dispose() }
        frameChoreographer.addListener(frameChoreographerListener)
        touchOracle.addSampleListener(oracleSampleListener)
    }

    private fun hitTestInteropView(point: CValue<CGPoint>): UIView? =
        point.useContents {
            val position = toDpOffset().toOffset(composeSceneDensity)
            val interopView = scene.hitTestInteropView(position)

            // Find a group of a holder associated with a given interop view or view controller
            interopView?.let { interopContainer.groupForInteropView(it) }
        }

    private fun onCancelAllTouches(touches: Set<*>) {
        activitiesHandler.onActivitiesEnded(touches.count())
        // Only the cancelled contacts are forgotten: another contact of the same gesture may
        // still be live and must keep its verdict.
        touches.forEach { indirectTouches.remove((it as UITouch).hashCode()) }
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
                updateIndirectSampling()
            }
            TouchesEventKind.MOVED -> {
                val state = indirectTouches[key] ?: return
                if (!state.usesOracle) return
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
        state.verdict =
            if (touchOracle.hasRing && radius >= CENTER_PAD_RADIUS) {
                IndirectTouchVerdict.IGNORED
            } else {
                IndirectTouchVerdict.CANDIDATE
            }
        swipeDebug {
            val verdict =
                if (state.verdict == IndirectTouchVerdict.IGNORED) {
                    "ignored-ring"
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
        if (!state.usesOracle) return
        when (state.verdict) {
            IndirectTouchVerdict.PENDING ->
                // First sample of this contact: it is the origin, not a movement.
                resolveIndirectTouchOrigin(state, sample, timestamp)
            IndirectTouchVerdict.CANDIDATE ->
                evaluateIndirectTouch(state, sample, timestamp, logSample)
            IndirectTouchVerdict.ARMED -> holdArmedIndirectTouch(state, timestamp)
            else -> {}
        }
    }

    /**
     * Feeds one display-link sample of the clickpad to every live oracle contact. UIKit reports
     * indirect movement sparsely, so this is what catches the origin of a contact in time and what
     * dispatches a swipe as soon as it is long enough.
     */
    private fun onOracleSample(sample: Offset?) {
        if (sample == null || isEvaluatingOracleSample) return
        if (indirectTouches.isEmpty()) return
        isEvaluatingOracleSample = true
        try {
            val timestamp = CACurrentMediaTime()
            purgeStaleIndirectTouches(timestamp)
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
                when (state.verdict) {
                    IndirectTouchVerdict.IGNORED,
                    IndirectTouchVerdict.CANCELLED,
                    IndirectTouchVerdict.DISPATCHED -> SWIPE_MAX_DURATION_S + DISPATCH_HOLD_S
                    else -> INDIRECT_CONTACT_MAX_AGE_S
                }
            // The tick stamps with CACurrentMediaTime and the contacts with UITouch.timestamp,
            // both of which are the system uptime.
            if (timestamp - state.beginTimestamp > maxAge) {
                iterator.remove()
                removed = true
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
        if (elapsed > SWIPE_MAX_DURATION_S) {
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
        if (dominant < distance || dominant < SWIPE_AXIS_DOMINANCE * other) {
            return
        }
        // Sign convention for dy differs by source: the oracle's dy is the GameController dpad's
        // absolute clickpad y, which is positive toward the top of the remote, so a positive dy
        // is an upward swipe. The fallback dy is a UIKit relative view location, positive
        // downward as usual for screen coordinates, so a positive dy there is a downward swipe.
        val key =
            if (absDx >= absDy) {
                if (dx > 0) Key.DirectionRight else Key.DirectionLeft
            } else if (state.usesOracle) {
                if (dy > 0) Key.DirectionUp else Key.DirectionDown
            } else {
                if (dy > 0) Key.DirectionDown else Key.DirectionUp
            }
        state.verdict = IndirectTouchVerdict.ARMED
        state.armedKey = key
        state.armedAt = timestamp
        swipeDebug { "SWIPE armed ${directionName(key)}" }
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
        dispatchIndirectSwipe(state)
    }

    /** Dispatches an armed swipe unless a clickpad press claimed the contact meanwhile. */
    private fun dispatchArmedIndirectTouch(state: IndirectTouchState, timestamp: Double) {
        if (isClickpadPressOverlapping(state.beginTimestamp, timestamp)) {
            state.verdict = IndirectTouchVerdict.CANCELLED
            swipeDebug { "SWIPE cancelled reason=press" }
            return
        }
        dispatchIndirectSwipe(state)
    }

    private fun dispatchIndirectSwipe(state: IndirectTouchState) {
        val key = state.armedKey ?: return
        swipeDebug { "SWIPE dispatch ${directionName(key)}" }
        state.verdict = IndirectTouchVerdict.DISPATCHED
        onKeyboardEvent(KeyEvent(key, KeyEventType.KeyDown))
        onKeyboardEvent(KeyEvent(key, KeyEventType.KeyUp))
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
        return true
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
            TouchesEventKind.BEGAN -> activitiesHandler.onActivitiesStarted(touches.count())
            TouchesEventKind.ENDED -> activitiesHandler.onActivitiesEnded(touches.count())
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
                keyboardModifiers =
                    PointerKeyboardModifiers(modifierFlags = event.modifierFlagsOrZero),
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

    fun setContent(parentCompositionContext: CompositionContext, content: @Composable () -> Unit) {
        _backgroundView.runOnceOnAppeared {
            scene.setContent(parentCompositionContext) {
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

    fun measureAndLayout() {
        scene.measureAndLayout()
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

    fun draw(canvas: Canvas) {
        didDrawSinceDisplayLink = true
        scene.draw(canvas)
    }

    val needsComposeSceneDraw: Boolean
        get() = scene.hasPendingDraw

    fun retrieveInteropTransaction(): InteropSyncTransaction =
        interopContainer.retrieveTransaction()

    fun retrievePendingViewUpdatesInteropTransaction(): InteropSyncTransaction =
        interopContainer.retrievePendingViewUpdatesTransaction()

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

        frameChoreographer.removeListener(frameChoreographerListener)
        prefetchScheduler.dispose()
        activitiesHandler.dispose()

        _overlayView.dispose()
        _backgroundView.dispose()

        _overlayView.removeFromSuperview()
        _backgroundView.removeFromSuperview()

        scene.close()
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
    }

    fun sceneWillDisappear() {
        // No keyboard manager to stop on tvOS
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
     * Measures the scene for a UIKit size proposal. [ComposeSceneSizing] derives [constraints] from
     * the hosting view's [screenDensity], but the tvOS scene is laid out at the squared
     * [composeSceneDensity] (see ComposeContainer), so rescale the constraints on the way in and
     * the measured size on the way out to keep the result in screen pixels.
     */
    fun measureSceneSize(constraints: Constraints): IntSize {
        val scale = composeSceneDensity.density / screenDensity.density
        if (scale == 1f) return scene.measureContent(constraints)
        fun Int.scaleIn() = if (this == Constraints.Infinity) this else (this * scale).roundToInt()
        val measured =
            scene.measureContent(
                Constraints(
                    minWidth = constraints.minWidth.scaleIn(),
                    maxWidth = constraints.maxWidth.scaleIn(),
                    minHeight = constraints.minHeight.scaleIn(),
                    maxHeight = constraints.maxHeight.scaleIn(),
                )
            )
        return IntSize(
            (measured.width / scale).roundToInt(),
            (measured.height / scale).roundToInt(),
        )
    }

    /**
     * Converts [UIPress] objects to [KeyEvent] and dispatches them to the appropriate handlers.
     * Handles key repeat by starting a coroutine that repeatedly dispatches KeyDown while a key is
     * held, similar to Android's key repeat behavior. Every press is dispatched to Compose as a
     * KeyDown on Began and as a KeyUp on Ended, whether or not the KeyDown was consumed, so
     * handlers that act on [KeyEventType.KeyUp] (back navigation, overlays) always see their KeyUp.
     * A Cancelled press dispatches nothing: the system claimed it, it is not a release.
     *
     * Returns what the caller must hand to `super`: only the Menu button can reach the system, and
     * only when Compose consumed neither of its phases (the app is at its root screen), in which
     * case the press is returned in [TvPressForwarding.replay] from its Ended phase. Every other
     * press is swallowed, so tvOS never suspends the app behind Compose's back.
     *
     * @param presses a [Set] of [UIPress] objects. Erasure happens due to K/N not supporting Obj-C
     *   lightweight generics.
     * @param pressesEvent the event the presses belong to, used to recognize presses that the
     *   responder chain delivers to this mediator twice.
     */
    fun onKeyboardPresses(presses: Set<*>, pressesEvent: UIPressesEvent?): TvPressForwarding {
        var replay: MutableSet<Any?>? = null
        var replayKeyIds: MutableList<Long>? = null
        var passThrough: MutableSet<Any?>? = null
        fun replayToSystem(press: Any?, keyId: Long) {
            val set = replay ?: mutableSetOf<Any?>().also { replay = it }
            set.add(press)
            val ids = replayKeyIds ?: mutableListOf<Long>().also { replayKeyIds = it }
            ids.add(keyId)
        }
        fun passToSystem(press: Any?) {
            val set = passThrough ?: mutableSetOf<Any?>().also { passThrough = it }
            set.add(press)
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
                // The replay of a Menu press nothing in Compose wanted, travelling up the
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
                    // A new Began for this press id means the previous press is over even if
                    // its Ended never arrived (the system keyboard overlay can absorb it), so
                    // clear here what the Ended phase would have cleared.
                    if (swallowedSelectKeyId == keyId) {
                        swallowedSelectKeyId = null
                        repeatingKeys.remove(keyId)?.cancel()
                    }
                    pressDispatchLog.takePendingMenu(keyId)
                    if (!onKeyboardEvent(event, keyId) && isMenu) {
                        // A Menu press is held back until its KeyUp has been offered to Compose
                        // as well; anything else is simply swallowed, tvOS must not act on it.
                        pressDispatchLog.setPendingMenu(keyId)
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
                        repeatingKeys[keyId] =
                            CoroutineScope(coroutineContext).launch {
                                delay(keyRepeatInitialDelayMs)
                                while (isActive) {
                                    onKeyboardEvent(repeatEvent, keyId)
                                    delay(keyRepeatIntervalMs)
                                }
                            }
                    }
                }
                UIPressPhase.UIPressPhaseEnded,
                UIPressPhase.UIPressPhaseCancelled -> {
                    repeatingKeys.remove(keyId)?.cancel()
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
                    if (wasPendingMenu && !consumed && isEnded) {
                        // Nothing in Compose wanted the Menu press on either phase: the app is
                        // at its root screen and tvOS has to move it to the background. A
                        // cancelled press is dropped instead — the user let go of the button
                        // outside of a completed press.
                        pressDispatchLog.markForwardedToSystem(pressesEvent, keyId)
                        replayToSystem(anyPress, keyId)
                    }
                }
                else -> Unit
            }
        }

        if (replay == null && passThrough == null) {
            return TvPressForwarding.None
        }
        val replayedKeyIds = replayKeyIds.orEmpty()
        return TvPressForwarding(
            replay = replay.orEmpty(),
            passThrough = passThrough.orEmpty(),
            // The short-circuit must not outlive the replay: `pressesEvent` can be null, and
            // then the log can't tell one event from the next.
            onReplayFinished = { replayedKeyIds.forEach(pressDispatchLog::unmarkForwardedToSystem) },
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

    private inner class IosPlatformContext : PlatformContext {
        override val windowInfo: WindowInfo
            get() = windowContext.windowInfo

        override val taskDispatchers: TaskDispatchers =
            object : TaskDispatchers {
                override val Default = Dispatchers.Default
                override val IO = Dispatchers.IO
            }
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

        override val rootForTestListener
            get() = this@ComposeSceneMediator.rootForTestListener

        override val windowInsets
            get() = this@ComposeSceneMediator.windowInsetsManager.windowInsets

        override val outOfFrameExecutor
            get() = this@ComposeSceneMediator.frameChoreographer.outOfFrameExecutor

        override val prefetchScheduler
            get() = this@ComposeSceneMediator.prefetchScheduler

        // On tvOS, Siri Remote touches are UITouchTypeIndirect which map to PointerType.Mouse.
        // Clearing focus on mouse-down would lose Compose focus on every remote swipe.
        override val isClearFocusOnMouseDownEnabled: Boolean
            get() = false

        override var isKeepScreenOnEnabled: Boolean
            get() = ApplicationIdleTimer.isDisabled
            set(value) {
                ApplicationIdleTimer.setIdleTimerState(this@ComposeSceneMediator, value)
            }

        override fun voteFrameRate(frameRate: Float, frameRateCategory: Float) {
            frameChoreographer.voteFrameRate(frameRate, frameRateCategory)
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
