package coil.size

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * A [SizeResolver] that measures the size of a [View].
 */
interface ViewSizeResolver<T : View> : SizeResolver {

    companion object {
        /**
         * Construct a [ViewSizeResolver] instance using the default [View] measurement implementation.
         */
        operator fun <T : View> invoke(view: T): ViewSizeResolver<T> {
            return object : ViewSizeResolver<T> {
                override val view = view
            }
        }
    }

    val view: T

    override suspend fun size(): Size {
        // Fast path: assume the View's height will match the data in its layout params.
        view.layoutParams?.let { layoutParams ->
            val width = layoutParams.width - view.paddingLeft - view.paddingRight
            val height = layoutParams.height - view.paddingTop - view.paddingBottom
            if (width > 0 && height > 0) {
                return PixelSize(width, height)
            }
        }

        // Wait for the view to be measured.
        return suspendCancellableCoroutine { continuation ->
            val viewTreeObserver = view.viewTreeObserver

            val preDrawListener = object : ViewTreeObserver.OnPreDrawListener {

                private var isResumed = false

                override fun onPreDraw(): Boolean {
                    if (!isResumed) {
                        isResumed = true
                        viewTreeObserver.removePreDrawListenerSafe(this)

                        val viewWidth = view.width
                        val viewHeight = view.height

                        val rawWidth = if (viewWidth == 0 && view.layoutParams?.width == ViewGroup.LayoutParams.WRAP_CONTENT) {
                            (view.parent as? View)?.width ?: 0
                        } else {
                            viewWidth - view.paddingLeft - view.paddingRight
                        }

                        val rawHeight = if (viewHeight == 0 && view.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT) {
                            (view.parent as? View)?.height ?: 0
                        } else {
                            viewHeight - view.paddingTop - view.paddingBottom
                        }

                        val size = PixelSize(
                            width = max(1, rawWidth),
                            height = max(1, rawHeight)
                        )
                        continuation.resume(size)
                    }
                    return true
                }
            }

            viewTreeObserver.addOnPreDrawListener(preDrawListener)

            continuation.invokeOnCancellation {
                viewTreeObserver.removePreDrawListenerSafe(preDrawListener)
            }
        }
    }

    private fun ViewTreeObserver.removePreDrawListenerSafe(victim: ViewTreeObserver.OnPreDrawListener) {
        when {
            isAlive -> removeOnPreDrawListener(victim)
            else -> view.viewTreeObserver.removeOnPreDrawListener(victim)
        }
    }
}
