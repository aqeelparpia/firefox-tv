/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.fragment

import android.arch.lifecycle.Lifecycle
import android.arch.lifecycle.LifecycleObserver
import android.arch.lifecycle.Observer
import android.arch.lifecycle.OnLifecycleEvent
import android.graphics.PointF
import android.os.Bundle
import android.support.annotation.UiThread
import android.text.TextUtils
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import kotlinx.android.synthetic.main.fragment_browser.*
import kotlinx.android.synthetic.main.fragment_browser.view.*
import org.mozilla.focus.R
import org.mozilla.focus.activity.MainActivity
import org.mozilla.focus.architecture.NonNullObserver
import org.mozilla.focus.browser.cursor.CursorKeyDispatcher
import org.mozilla.focus.browser.cursor.CursorViewModel
import org.mozilla.focus.ext.getAccessibilityManager
import org.mozilla.focus.ext.isVoiceViewEnabled
import org.mozilla.focus.session.NullSession
import org.mozilla.focus.session.Session
import org.mozilla.focus.session.SessionCallbackProxy
import org.mozilla.focus.session.SessionManager
import org.mozilla.focus.telemetry.TelemetryWrapper
import org.mozilla.focus.telemetry.UrlTextInputLocation
import org.mozilla.focus.web.IWebView
import org.mozilla.focus.web.IWebViewLifecycleFragment
import org.mozilla.focus.widget.BrowserNavigationOverlay
import org.mozilla.focus.browser.cursor.CursorView
import org.mozilla.focus.widget.InlineAutocompleteEditText
import org.mozilla.focus.widget.NavigationEvent

private const val ARGUMENT_SESSION_UUID = "sessionUUID"
private const val SCROLL_MULTIPLIER = 45

/**
 * Fragment for displaying the browser UI.
 */
class BrowserFragment : IWebViewLifecycleFragment(), BrowserNavigationOverlay.NavigationEventHandler {
    companion object {
        const val FRAGMENT_TAG = "browser"

        @JvmStatic
        fun createForSession(session: Session) = BrowserFragment().apply {
            arguments = Bundle().apply { putString(ARGUMENT_SESSION_UUID, session.uuid) }
        }
    }

    // IWebViewLifecycleFragment expects a value for these properties before onViewCreated. We use a getter
    // for the properties that reference session because it is lateinit.
    override lateinit var session: Session
    override val initialUrl get() = session.url.value
    override val iWebViewCallback get() = SessionCallbackProxy(session, BrowserIWebViewCallback(this))

    // getUrl() is used for things like sharing the current URL. We could try to use the webview,
    // but sometimes it's null, and sometimes it returns a null URL. Sometimes it returns a data:
    // URL for error pages. The URL we show in the toolbar is (A) always correct and (B) what the
    // user is probably expecting to share, so lets use that here:
    //
    // Note: when refactoring, I removed the url view and replaced urlView.setText with assignment
    // to this url variable - should be equivalent.
    var url: String? = null
        private set

    private val sessionManager = SessionManager.getInstance()

    /**
     * Encapsulates the cursor's components. If this value is null, the Cursor is not attached
     * to the view hierarchy.
     */
    var cursor: CursorController? = null
        @UiThread get set // Set from the UI thread so serial access is required for simplicity.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initSession()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.browserOverlay!!.setNavigationEventHandler(this)
        super.onViewCreated(view, savedInstanceState)
    }

    private fun initSession() {
        val sessionUUID = arguments.getString(ARGUMENT_SESSION_UUID)
                ?: throw IllegalAccessError("No session exists")
        session = if (sessionManager.hasSessionWithUUID(sessionUUID))
            sessionManager.getSessionByUUID(sessionUUID)
        else
            NullSession()

        setBlockingEnabled(session.isBlockingEnabled)
        session.url.observe(this, Observer { url -> this@BrowserFragment.url = url })
        session.loading.observe(this, object : NonNullObserver<Boolean>() {
            public override fun onValueChanged(loading: Boolean) {
                val activity = activity as MainActivity
                if (!loading && activity.isReloadingForYoutubeDrawerClosed) {
                    activity.isReloadingForYoutubeDrawerClosed = false

                    // We send a play event which:
                    // - If we're on the video selection page, does nothing.
                    // - If we're in a fullscreen video, will show the play/pause controls on the screen so
                    // we don't just see a black screen.
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                }
            }
        })
    }

    override fun onEvent(event: NavigationEvent, value: String?, autocompleteResult: InlineAutocompleteEditText.AutocompleteResult?) {
        when (event) {
            NavigationEvent.BACK -> if (canGoBack()) goBack()
            NavigationEvent.FORWARD -> if (canGoForward()) goForward()
            NavigationEvent.RELOAD -> reload()
            NavigationEvent.HOME -> (activity as MainActivity).showHomeScreen()
            NavigationEvent.SETTINGS -> (activity as MainActivity).showSettingsScreen()
            NavigationEvent.LOAD -> (activity as MainActivity).onTextInputUrlEntered(value!!, autocompleteResult!!, UrlTextInputLocation.MENU)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val layout = inflater.inflate(R.layout.fragment_browser, container, false)
        cursor = CursorController(this, layout.cursorView)
        lifecycle.addObserver(cursor!!)
        return layout
    }

    override fun onDestroyView() {
        super.onDestroyView()
        lifecycle.removeObserver(cursor!!)
        cursor = null
    }

    fun onBackPressed(): Boolean {
        if (canGoBack()) {
            // Go back in web history
            goBack()
            TelemetryWrapper.browserBackControllerEvent()
        } else {
            fragmentManager.popBackStack()
            SessionManager.getInstance().removeCurrentSession()
        }

        return true
    }

    // TODO: When all calling code is kotlin, rm these - they're unnecessary with cascading nulls.
    fun canGoForward() = webview?.canGoForward() ?: false
    fun canGoBack() = webview?.canGoBack() ?: false
    fun goBack() = webview?.goBack()
    fun goForward() = webview?.goForward()

    fun loadUrl(url: String) {
        val webView = webView
        if (webView != null && !TextUtils.isEmpty(url)) {
            webView.loadUrl(url)
        }
    }

    fun reload() = webView?.reload()
    fun setBlockingEnabled(enabled: Boolean) = webview?.setBlockingEnabled(enabled)

    fun dispatchKeyEvent(event: KeyEvent): Boolean = cursor?.keyDispatcher?.dispatchKeyEvent(event) ?: false
}

private class BrowserIWebViewCallback(
        private val browserFragment: BrowserFragment
) : IWebView.Callback {

    private var fullscreenCallback: IWebView.FullscreenCallback? = null

    override fun onPageStarted(url: String) {}
    override fun onPageFinished(isSecure: Boolean) {}
    override fun onProgress(progress: Int) {}

    override fun onURLChanged(url: String) {}
    override fun onRequest(isTriggeredByUserGesture: Boolean) {}

    override fun countBlockedTracker() {}
    override fun resetBlockedTrackers() {}

    override fun onBlockingStateChanged(isBlockingEnabled: Boolean) {}

    override fun onLongPress(hitTarget: IWebView.HitTarget) {}

    override fun onEnterFullScreen(callback: IWebView.FullscreenCallback, view: View?) {
        fullscreenCallback = callback
        if (view == null) return

        with (browserFragment) {
            // Hide browser UI and web content
            browserContainer.visibility = View.INVISIBLE

            val params = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            videoContainer.addView(view, params)
            videoContainer.visibility = View.VISIBLE
        }
    }

    override fun onExitFullScreen() {
        with (browserFragment) {
            videoContainer.removeAllViews()
            videoContainer.visibility = View.GONE

            browserContainer.visibility = View.VISIBLE
        }

        fullscreenCallback?.fullScreenExited()
        fullscreenCallback = null
    }
}

/**
 * Encapsulates interactions of the Cursors components. It has the following responsibilities:
 * - Data: references to each Cursor component
 * - Controller: manages interactions between the components and the parent fragment
 * - Lifecycle management: provides lifecycle callbacks; nulling a reference to this controller
 * will also prevent access to the components beyond the lifecycle.
 *
 * For simplicity, the lifecycle of the ViewModel and the KeyDispatcher are the same as the View.
 *
 * When using this class, don't forget to add it as a [LifecycleObserver].
 */
class CursorController(
        // Our lifecycle is shorter than BrowserFragment, so we can hold a reference.
        private val browserFragment: BrowserFragment,
        val view: CursorView
) : AccessibilityManager.TouchExplorationStateChangeListener, LifecycleObserver {
    var isEnabled = true
        set(value) {
            field = value
            keyDispatcher.isEnabled = value
            view.visibility = if (value) View.VISIBLE else View.GONE
        }

    val viewModel = CursorViewModel(onUpdate = { x, y, scrollVel ->
        view.updatePosition(x, y)
        scrollWebView(scrollVel)
    }, simulateTouchEvent = { browserFragment.activity.dispatchTouchEvent(it) })

    val keyDispatcher = CursorKeyDispatcher(isEnabled, viewModel)

    private val isLoadingObserver = CursorIsLoadingObserver()

    init {
        view.onLayoutChanged = { width, height ->
            viewModel.maxBounds = PointF(width.toFloat(), height.toFloat())
        }
    }

    /** Gets the current state of the browser and updates the cursor enabled state accordingly. */
    private fun setEnabledForCurrentState() {
        // These sources have their own navigation controls.
        val isYoutubeTV = browserFragment.webview?.getUrl()?.contains("youtube.com/tv") ?: false
        isEnabled = !isYoutubeTV && !browserFragment.context.isVoiceViewEnabled()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onStart() {
        browserFragment.context.getAccessibilityManager().addTouchExplorationStateChangeListener(this)
        setEnabledForCurrentState() // VoiceView state may change.

        browserFragment.session.loading.observe(browserFragment, isLoadingObserver)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onStop() {
        browserFragment.context.getAccessibilityManager().removeTouchExplorationStateChangeListener(this)

        browserFragment.session.loading.removeObserver(isLoadingObserver)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun onPause() {
        viewModel.cancelUpdates()
    }

    override fun onTouchExplorationStateChanged(isEnabled: Boolean) {
        setEnabledForCurrentState()
    }

    private fun scrollWebView(scrollVel: PointF) {
        val scrollX = Math.round(scrollVel.x * SCROLL_MULTIPLIER)
        val scrollY = Math.round(scrollVel.y * SCROLL_MULTIPLIER)
        browserFragment.webView?.flingScroll(scrollX, scrollY)
    }

    private inner class CursorIsLoadingObserver : NonNullObserver<Boolean>() {
        override fun onValueChanged(isLoading: Boolean) {
            setEnabledForCurrentState()
        }
    }
}
