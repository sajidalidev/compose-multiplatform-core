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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.copy
import androidx.compose.ui.input.key.toComposeEvent
import androidx.compose.ui.input.pointer.HistoricalChange
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.navigationevent.BackNavigationEventInput
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
import androidx.compose.ui.uikit.InterfaceOrientation
import androidx.compose.ui.uikit.LocalUIView
import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.uikit.density
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toDpOffset
import androidx.compose.ui.unit.toDpRect
import androidx.compose.ui.unit.toDpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.unit.roundToIntSize
import androidx.compose.ui.unit.toOffset
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.LocalInteropContainer
import androidx.compose.ui.viewinterop.TrackInteropPlacementContainer
import androidx.compose.ui.viewinterop.UIKitInteropContainer
import androidx.compose.ui.viewinterop.UIKitInteropTransaction
import androidx.compose.ui.window.FocusedViewsList
import androidx.compose.ui.window.MetalRedrawer
import kotlin.coroutines.CoroutineContext
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

/**
 * A reason for why touches are sent to Compose.
 * Mirrors the iOS [TouchesEventKind] which is not available in tvosMain.
 */
internal enum class TouchesEventKind {
    BEGAN,
    MOVED,
    ENDED
}

/**
 * tvOS specific-implementation of [PlatformContext.SemanticsOwnerListener] used to track changes in [SemanticsOwner].
 *
 * @property view The UI container associated with the semantics owner.
 * @property coroutineContext The coroutine context to use for handling semantics changes.
 * @property performEscape A lambda to delegate accessibility escape operation. Returns true if the escape was handled, false otherwise.
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
            accessibilityMediator = AccessibilityMediator(
                view,
                semanticsOwner,
                coroutineContext,
                performEscape,
                onScreenReaderActive
            ).also {
                it.isEnabled = isEnabled
            }
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

    val hasInvalidations: Boolean get() = accessibilityMediator?.hasPendingInvalidations ?: false

    fun dispose() {
        accessibilityMediator?.dispose()
        accessibilityMediator = null
    }
}

/**
 * A simple overlay view for tvOS that handles touch events from the Siri Remote touch surface
 * and keyboard presses. Unlike the iOS [OverlayInputView], this does not handle hover gestures,
 * scroll gestures, or native text input, as those are not applicable on tvOS.
 */
private class TvOverlayInputView(
    private var hitTestInteropView: (point: CValue<CGPoint>) -> UIView?,
    private var isPointInsideInteractionBounds: (CValue<CGPoint>) -> Boolean,
    private var onTouchesEvent: (touches: Set<*>, event: UIEvent?, phase: TouchesEventKind) -> PointerEventResult,
    private var onCancelAllTouches: (touches: Set<*>) -> Unit,
    private var onKeyboardPresses: (Set<*>) -> Unit,
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
        onKeyboardPresses(presses)
        // Do not call super to prevent the back button from exiting the app
    }

    override fun pressesEnded(presses: Set<*>, withEvent: UIPressesEvent?) {
        onKeyboardPresses(presses)
    }

    override fun pressesCancelled(presses: Set<*>, withEvent: UIPressesEvent?) {
        onKeyboardPresses(presses)
    }

    override fun touchesBegan(touches: Set<*>, withEvent: UIEvent?) {
        withEvent?.let { event ->
            onTouchesEvent(touches, event, TouchesEventKind.BEGAN)
        }
        super.touchesBegan(touches, withEvent)
    }

    override fun touchesMoved(touches: Set<*>, withEvent: UIEvent?) {
        withEvent?.let { event ->
            onTouchesEvent(touches, event, TouchesEventKind.MOVED)
        }
        super.touchesMoved(touches, withEvent)
    }

    override fun touchesEnded(touches: Set<*>, withEvent: UIEvent?) {
        withEvent?.let { event ->
            onTouchesEvent(touches, event, TouchesEventKind.ENDED)
        }
        super.touchesEnded(touches, withEvent)
    }

    override fun touchesCancelled(touches: Set<*>, withEvent: UIEvent?) {
        touches?.let { t ->
            onCancelAllTouches(t)
        }
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
        onKeyboardPresses = {}
        onOutsidePointerEvent = {}
        onTouchesEvent = { _, _, _ -> PointerEventResult() }
        onCancelAllTouches = {}
    }
}

/**
 * A simple background view for tvOS that handles touch events directed at interop views
 * located below the rendering canvas. Unlike the iOS [BackgroundInputView], this does not use
 * gesture recognizers.
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
            ?.let {
                it.hitTest(
                    point = convertPoint(point, toView = it),
                    withEvent = withEvent
                )
            }
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
    private val navigationEventInput: BackNavigationEventInput,
    interfaceOrientationState: State<InterfaceOrientation>,
    composeSceneFactory: (
        invalidate: () -> Unit,
        platformContext: PlatformContext,
        frameRecomposer: FrameRecomposer,
    ) -> ComposeScene
) {
    private var onPreviewKeyEvent: (KeyEvent) -> Boolean = { false }
    private var onKeyEvent: (KeyEvent) -> Boolean = { false }

    // Key repeat state: repeatedly dispatch KeyDown while a key is held
    private val repeatingKeys = mutableMapOf<Long, Job>()

    // Tracks the start position of each Siri Remote indirect touch for swipe-to-focus fallback.
    private val indirectTouchStartPositions = mutableMapOf<Int, Offset>()
    // True when a Select press fired while at least one indirect touch was in flight. The
    // Siri Remote trackpad is itself the Select button; clicking it produces both a UIPress
    // and a UITouch whose ENDED position can drift several dp from where it began. Without
    // this flag, that drift is misread as a swipe.
    private var selectPressedDuringIndirectTouch = false
    private val keyRepeatInitialDelayMs = 500L
    private val keyRepeatIntervalMs = 50L
    // Minimum swipe distance (dp) on the Siri Remote trackpad to trigger a focus move.
    private val INDIRECT_SWIPE_THRESHOLD_DP = 40f
    private var platformScreenReader = object : PlatformScreenReader {
        override var isActive by mutableStateOf(false)
    }

    private val isActive get() = coroutineContext.isActive

    private val viewConfiguration: ViewConfiguration =
        object : ViewConfiguration by PlatformContext.DefaultViewConfiguration {
            override val touchSlop: Float
                get() = with(screenDensity) {
                    CUPERTINO_TOUCH_SLOP.dp.toPx()
                }
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
    val screenDensity: Density get() = _overlayView.density

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

    val hasInteropViews: Boolean get() = interopContainer.hasInteropViews

    /**
     * Primary view to handle user input from the Siri Remote touch surface.
     * Also used as a root container view for accessibility.
     */
    private val _overlayView = TvOverlayInputView(
        hitTestInteropView = ::hitTestInteropView,
        isPointInsideInteractionBounds = ::isPointInsideInteractionBounds,
        onTouchesEvent = ::onTouchesEvent,
        onCancelAllTouches = ::onCancelAllTouches,
        onKeyboardPresses = ::onKeyboardPresses,
    )

    val overlayView: UIView get() = _overlayView

    /**
     * A holder for interop views that located below the Metal canvas.
     * The view handles user touches that occur only over the interop views located on it.
     */
    private val _backgroundView = TvBackgroundInputView(
        onMovedToWindow = ::focusOverlayViewIfNeeded,
        onLayoutSubviews = ::updateLayout,
        hitTestInteropView = ::hitTestInteropView,
        isPointInsideInteractionBounds = ::isPointInsideInteractionBounds,
    )

    val backgroundView: UIView get() = _backgroundView

    /**
     * Container for managing UIKitView and UIKitViewController
     */
    private val interopContainer = UIKitInteropContainer(
        overlayContainer = _overlayView,
        backgroundContainer = _backgroundView,
        requestRedraw = redrawer::setNeedsRedraw
    )

    private val windowInsetsManager = UIKitWindowInsetsManager(
        windowInsetsViews = listOf(
            { _overlayView },
            { windowContext.window?.rootViewController?.view },
        ),
        interfaceOrientation = interfaceOrientationState
    )

    private val tvOSTextInputService = TvOSTextInputService(
        view = _overlayView,
        focusedViewsList = focusedViewsList,
    )

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
            onScreenReaderActive = { platformScreenReader.isActive = it }
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
        get() = frameRecomposer.hasPendingWork() ||
            scene.hasInvalidations() ||
            isLayoutTransitionAnimating ||
            semanticsOwnerListener.hasInvalidations

    init {
        coroutineContext.job.invokeOnCompletion { dispose() }
    }

    private fun hitTestInteropView(point: CValue<CGPoint>): UIView? =
        point.useContents {
            val position = toDpOffset().toOffset(composeSceneDensity)
            val interopView = scene.hitTestInteropView(position)

            // Find a group of a holder associated with a given interop view or view controller
            interopView?.let {
                interopContainer.groupForInteropView(it)
            }
        }

    private fun onCancelAllTouches(touches: Set<*>) {
        redrawer.ongoingInteractionEventsCount -= touches.count()
        indirectTouchStartPositions.clear()
        selectPressedDuringIndirectTouch = false
        scene.cancelPointerInput()
    }

    /**
     * Converts [UITouch] objects from [touches] to [ComposeScenePointer] and dispatches them to the appropriate handlers.
     * @param touches a [Set] of [UITouch] objects. Erasure happens due to K/N not supporting Obj-C lightweight generics.
     * @param event the [UIEvent] associated with the touches
     * @param eventKind the [TouchesEventKind] of the touches
     */
    private fun onTouchesEvent(
        touches: Set<*>,
        event: UIEvent?,
        eventKind: TouchesEventKind
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
                val position = touch.offsetInView(_backgroundView, screenDensity.density)
                when (eventKind) {
                    TouchesEventKind.BEGAN -> {
                        if (indirectTouchStartPositions.isEmpty()) {
                            selectPressedDuringIndirectTouch = false
                        }
                        indirectTouchStartPositions[touch.hashCode()] = position
                    }
                    TouchesEventKind.ENDED -> {
                        val startPos = indirectTouchStartPositions.remove(touch.hashCode())
                        val suppressSwipe = selectPressedDuringIndirectTouch
                        if (indirectTouchStartPositions.isEmpty()) {
                            selectPressedDuringIndirectTouch = false
                        }
                        if (startPos == null || suppressSwipe) continue
                        val dx = position.x - startPos.x
                        val dy = position.y - startPos.y
                        val threshold = with(screenDensity) { INDIRECT_SWIPE_THRESHOLD_DP.dp.toPx() }
                        if (dx * dx + dy * dy >= threshold * threshold) {
                            val key = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
                                if (dx > 0) Key.DirectionRight else Key.DirectionLeft
                            } else {
                                if (dy > 0) Key.DirectionDown else Key.DirectionUp
                            }
                            onKeyboardEvent(KeyEvent(key, KeyEventType.KeyDown))
                            onKeyboardEvent(KeyEvent(key, KeyEventType.KeyUp))
                        }
                    }
                    TouchesEventKind.MOVED -> {}
                }
            } else {
                pointerTouches.add(touch)
            }
        }

        if (pointerTouches.isEmpty()) {
            previousButtonMask = event.buttonMaskOrZero
            if (eventKind != TouchesEventKind.MOVED) previousTouchEventKind = eventKind
            return PointerEventResult(anyMovementConsumed = false)
        }

        val pointers = pointerTouches.mapIndexed { index, touch ->
            val position = touch.offsetInView(_backgroundView, screenDensity.density)
            val pointerType = when (touch.type) {
                UITouchTypeDirect -> PointerType.Touch
                UITouchTypeIndirectPointer -> PointerType.Mouse
                else -> PointerType.Touch
            }
            val id = touch.hashCode().toLong().takeIf {
                pointerType != PointerType.Mouse
            } ?: index.toLong()
            ComposeScenePointer(
                id = PointerId(id),
                position = position,
                pressed = touch.isPressed,
                type = pointerType,
                pressure = touch.force.toFloat(),
                historical = event?.historicalChangesForTouch(
                    touch,
                    _overlayView,
                    screenDensity.density
                ) ?: emptyList()
            )
        }

        // UIKit sends buttonMask that was before the release action. It should be empty if no
        // pressed pointers left.
        val pointerButtonsMask = event.buttonMaskOrZero.takeIf {
            pointers.any { it.pressed }
        } ?: 0L

        return scene.sendPointerEvent(
            eventType = eventKind.toPointerEventType(),
            pointers = pointers,
            timeMillis = event.timeMillis,
            nativeEvent = event,
            button = event?.getButton(previousButtonMask, eventKind, previousTouchEventKind),
            buttons = PointerButtons(pointerButtonsMask),
            keyboardModifiers = PointerKeyboardModifiers(event.modifierFlagsOrZero)
        ).also {
            previousButtonMask = event.buttonMaskOrZero
            if (eventKind != TouchesEventKind.MOVED) previousTouchEventKind = eventKind
        }
    }
    private var previousButtonMask: Long = 0L
    private var previousTouchEventKind: TouchesEventKind? = null

    private var lastFocusedRect: Rect? = null
    private fun getFocusedRect(): Rect? {
        return scene.focusManager.getFocusRect(afterLayout = false)?.also {
            lastFocusedRect = it
        } ?: lastFocusedRect
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
    fun prepareAndGetSizeTransitionAnimation(withProgress: suspend ((Float) -> Unit) -> Unit): suspend () -> Unit {
        isLayoutTransitionAnimating = true

        val initialWindowInsets = windowInsetsManager.windowInsetsSnapshot()
        val initialSize = scene.size?.toSize() ?: return {}

        return {
            try {
                withProgress { progress ->
                    windowInsetsManager.updateInsetsForAnimation(
                        initialWindowInsets = initialWindowInsets,
                        progress = progress
                    )
                    composeSceneSize = lerp(
                        start = initialSize,
                        stop = currentViewSize,
                        fraction = progress
                    ).roundToIntSize()
                }
            } finally {
                isLayoutTransitionAnimating = false
                updateLayout()
            }
        }
    }

    fun render(canvas: Canvas, nanoTime: Long) {
        with(sceneRenderingScope) {
            scene.render(frameRecomposer, canvas, nanoTime)
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
            content = content
        )

    private fun dispose() {
        repeatingKeys.values.forEach { it.cancel() }
        repeatingKeys.clear()
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

    /**
     * Updates the [ComposeScene] with the properties derived from the [_overlayView].
     */
    private fun updateLayout() {
        if (isLayoutTransitionAnimating) {
            return
        }
        windowInsetsManager.updateInsets()
        composeSceneSize = currentViewSize.roundToIntSize()
        interactionBounds = with(screenDensity) {
            _overlayView.bounds.toDpRect().toRect().roundToIntRect()
        }
    }

    private val currentViewSize: Size get() {
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
    }

    fun didUpdateFocusInContext() {
        scene.focusManager.takeFocus(FocusDirection.Enter)
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
        onKeyEvent: ((KeyEvent) -> Boolean)?
    ) {
        this.onPreviewKeyEvent = onPreviewKeyEvent ?: { false }
        this.onKeyEvent = onKeyEvent ?: { false }
    }

    /**
     * Converts [UIPress] objects to [KeyEvent] and dispatches them to the appropriate handlers.
     * Handles key repeat by starting a coroutine that repeatedly dispatches KeyDown while a key
     * is held, similar to Android's key repeat behavior.
     * @param presses a [Set] of [UIPress] objects. Erasure happens due to K/N not supporting Obj-C lightweight generics.
     */
    fun onKeyboardPresses(presses: Set<*>) {
        presses.forEach { anyPress ->
            val press = anyPress as UIPress
            val rawEvent = press.toComposeEvent()
            // Siri Remote's Menu button is tvOS's back gesture.
            val event = if (rawEvent.key == Key.Menu) rawEvent.copy(key = Key.Back) else rawEvent
            val keyId = press.key?.keyCode?.toLong() ?: -(press.type.toLong() + 1L)

            // The Siri Remote trackpad doubles as the Select button: clicking it produces
            // a Select press alongside an indirect touch whose end position can drift
            // enough to look like a swipe. Mark the in-flight touch so its ENDED branch
            // doesn't dispatch a directional key.
            if (event.key == Key.DirectionCenter &&
                press.phase == UIPressPhase.UIPressPhaseBegan &&
                indirectTouchStartPositions.isNotEmpty()
            ) {
                selectPressedDuringIndirectTouch = true
            }

            when (press.phase) {
                UIPressPhase.UIPressPhaseBegan -> {
                    onKeyboardEvent(event)

                    if (repeatingKeys[keyId]?.isActive != true) {
                        val repeatEvent = event.copy(isRepeat = true)
                        repeatingKeys[keyId] = CoroutineScope(coroutineContext).launch {
                            delay(keyRepeatInitialDelayMs)
                            while (isActive) {
                                onKeyboardEvent(repeatEvent)
                                delay(keyRepeatIntervalMs)
                            }
                        }
                    }
                }
                UIPressPhase.UIPressPhaseEnded -> {
                    repeatingKeys.remove(keyId)?.cancel()
                    onKeyboardEvent(event)
                }
                UIPressPhase.UIPressPhaseCancelled -> {
                    repeatingKeys.remove(keyId)?.cancel()
                }
                else -> Unit
            }
        }
    }

    private fun onKeyboardEvent(keyEvent: KeyEvent): Boolean {
        // Show the tvOS system keyboard when the user presses Select on a focused text field.
        // The keyboard must not open on D-pad navigation alone (only on explicit Select press).
        if (tvOSTextInputService.activeRequest != null &&
            !tvOSTextInputService.isKeyboardVisible &&
            keyEvent.key == Key.DirectionCenter &&
            keyEvent.type == KeyEventType.KeyDown
        ) {
            tvOSTextInputService.showKeyboard()
            return true
        }

        // tvOS text fields must not consume D-pad presses — there's no in-line cursor on
        // tvOS, so consuming the key would trap focus inside the field. While a Compose
        // text field holds input focus, route D-pad directly to focus traversal instead
        // of letting `scene.sendKeyEvent` deliver it to the field.
        if (tvOSTextInputService.activeRequest != null &&
            !tvOSTextInputService.isKeyboardVisible
        ) {
            val direction = keyEvent.toFocusDirection()
            if (direction != null) {
                if (onPreviewKeyEvent(keyEvent)) return true
                if (keyEvent.type == KeyEventType.KeyDown) {
                    scene.focusManager.moveFocus(direction)
                }
                return true
            }
        }

        return onPreviewKeyEvent(keyEvent)
            || scene.sendKeyEvent(keyEvent)
            || onKeyEvent(keyEvent)
            || navigationEventInput.onKeyEvent(keyEvent)
    }

    private fun KeyEvent.toFocusDirection(): FocusDirection? = when (key) {
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        Key.DirectionLeft -> FocusDirection.Left
        Key.DirectionRight -> FocusDirection.Right
        else -> null
    }

    private inner class PlatformContextImpl : PlatformContext {
        override val windowInfo: WindowInfo get() = windowContext.windowInfo
        override val architectureComponentsOwner get() = this@ComposeSceneMediator.architectureComponentsOwner
        override val screenReader: PlatformScreenReader get() = platformScreenReader

        override val hapticFeedback: HapticFeedback by lazy(LazyThreadSafetyMode.NONE) {
            TvOSHapticFeedback()
        }

        override fun convertLocalToWindowPosition(localPosition: Offset): Offset =
            windowContext.convertLocalToWindowPosition(_overlayView, localPosition)

        override fun convertWindowToLocalPosition(positionInWindow: Offset): Offset =
            windowContext.convertWindowToLocalPosition(_overlayView, positionInWindow)

        override fun convertLocalToScreenPosition(localPosition: Offset): Offset =
            windowContext.convertLocalToScreenPosition(_overlayView, localPosition)

        override fun convertScreenToLocalPosition(positionOnScreen: Offset): Offset =
            windowContext.convertScreenToLocalPosition(_overlayView, positionOnScreen)

        override val viewConfiguration get() = this@ComposeSceneMediator.viewConfiguration

        override val inputModeManager by lazy(LazyThreadSafetyMode.NONE) {
            DefaultInputModeManager(InputMode.Touch)
        }
        override val semanticsOwnerListener get() = this@ComposeSceneMediator.semanticsOwnerListener
        override val windowInsets get() = this@ComposeSceneMediator.windowInsetsManager.windowInsets
        override val outOfFrameExecutor get() = this@ComposeSceneMediator.redrawer.outOfFrameExecutor
        // On tvOS, Siri Remote touches are UITouchTypeIndirect which map to PointerType.Mouse.
        // Clearing focus on mouse-down would lose Compose focus on every remote swipe.
        override val isClearFocusOnMouseDownEnabled: Boolean get() = false

        override var isKeepScreenOnEnabled: Boolean
            get() = UIKitIdleTimerManager.isIdleTimerDisabled
            set(value) { UIKitIdleTimerManager.setIdleTimerState(this@ComposeSceneMediator, value) }

        override fun voteFrameRate(frameRate: Float, frameRateCategory: Float) {
            redrawer.voteFrameRate(frameRate, frameRateCategory)
        }

        override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
            tvOSTextInputService.startInput(request)
            try {
                // Suspend until Compose cancels this session (text field loses focus).
                // The keyboard UI is shown/hidden independently via showKeyboard()/hideKeyboard();
                // we do NOT cancel here on keyboard dismissal so the user can re-open the
                // keyboard by pressing Select on the same field without losing and regaining focus.
                kotlinx.coroutines.suspendCancellableCoroutine<Nothing> { continuation ->
                    continuation.invokeOnCancellation {
                        tvOSTextInputService.stopInput()
                    }
                }
            } finally {
                tvOSTextInputService.stopInput()
            }
        }
    }
}

private fun UIEvent.getButton(
    previousButtonMask: Long,
    eventKind: TouchesEventKind,
    previousEventKind: TouchesEventKind?
): PointerButton? =
    if (eventKind == TouchesEventKind.MOVED) {
        null
    } else if (buttonMaskOrZero and UIEventButtonMaskPrimary != 0L &&
        (previousButtonMask and UIEventButtonMaskPrimary == 0L ||
            eventKind != previousEventKind)) {
        PointerButton.Primary
    } else if (buttonMaskOrZero and UIEventButtonMaskSecondary != 0L &&
        (previousButtonMask and UIEventButtonMaskSecondary == 0L ||
            eventKind != previousEventKind)) {
        PointerButton.Secondary
    } else {
        null
    }

private val UIEvent?.timeMillis: Long get() {
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
    density: Float
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

private val UIEvent?.buttonMaskOrZero: Long get() = 0L

private val UIEvent?.modifierFlagsOrZero: Long get() =
    this?.modifierFlags ?: 0L

private val UITouch.isPressed
    get() = when (phase) {
        UITouchPhase.UITouchPhaseEnded, UITouchPhase.UITouchPhaseCancelled -> false
        else -> true
    }

private fun UITouch.offsetInView(view: UIView, density: Float): Offset =
    locationInView(view).useContents {
        Offset(x.toFloat() * density, y.toFloat() * density)
    }
