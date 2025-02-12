@file:Suppress("unused")

package coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.annotation.FloatRange
import coil.annotation.BuilderMarker
import coil.drawable.CrossfadeDrawable
import coil.target.ImageViewTarget
import coil.util.Utils
import coil.util.getDrawableCompat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/** Builder for an [ImageLoader]. */
@BuilderMarker
class ImageLoaderBuilder(private val context: Context) {

    private var okHttpClient: OkHttpClient? = null
    private var okHttpClientBuilder: (OkHttpClient.Builder.() -> Unit)? = null

    private var registry: ComponentRegistry? = null

    private var availableMemoryPercentage: Double = Utils.getDefaultAvailableMemoryPercentage(context)
    private var bitmapPoolPercentage: Double = Utils.getDefaultBitmapPoolPercentage()

    private var defaults = DefaultRequestOptions()

    /**
     * Set the [OkHttpClient] to be used for network requests.
     *
     * Prefer `okHttpClient(OkHttpClient.Builder.() -> Unit)` if possible,
     * as the default [OkHttpClient] instance is optimized for Coil.
     */
    fun okHttpClient(client: OkHttpClient) = apply {
        this.okHttpClient = client
        this.okHttpClientBuilder = null
    }

    /**
     * Set the callback that is invoked when building the default [OkHttpClient].
     *
     * @see buildDefaultOkHttpClient
     */
    fun okHttpClient(builder: OkHttpClient.Builder.() -> Unit) = apply {
        this.okHttpClientBuilder = builder
        this.okHttpClient = null
    }

    /**
     * Build and set the [ComponentRegistry].
     */
    inline fun componentRegistry(builder: ComponentRegistry.Builder.() -> Unit) = apply {
        componentRegistry(ComponentRegistry(builder))
    }

    /**
     * Set the [ComponentRegistry].
     */
    fun componentRegistry(registry: ComponentRegistry) = apply {
        this.registry = registry
    }

    /**
     * Set the percentage of available memory to devote to this [ImageLoader]'s memory cache and bitmap pool.
     *
     * Setting this to 0 disables memory caching and bitmap pooling.
     *
     * Default: [Utils.getDefaultAvailableMemoryPercentage]
     */
    fun availableMemoryPercentage(@FloatRange(from = 0.0, to = 1.0) multiplier: Double) = apply {
        require(multiplier in 0.0..1.0) { "Multiplier must be within the range [0.0, 1.0]." }
        this.availableMemoryPercentage = multiplier
    }

    /**
     * Set the percentage of memory allocated to this [ImageLoader] to allocate to bitmap pooling.
     *
     * i.e. Setting [availableMemoryPercentage] to 0.25 and [bitmapPoolPercentage] to 0.5 allows this ImageLoader
     * to use 25% of the app's total memory and splits that memory 50/50 between the bitmap pool and memory cache.
     *
     * Setting this to 0 disables bitmap pooling.
     *
     * Default: [Utils.getDefaultBitmapPoolPercentage]
     */
    fun bitmapPoolPercentage(@FloatRange(from = 0.0, to = 1.0) multiplier: Double) = apply {
        require(multiplier in 0.0..1.0) { "Multiplier must be within the range [0.0, 1.0]." }
        this.bitmapPoolPercentage = multiplier
    }

    /**
     * The default [CoroutineDispatcher] to run image requests on.
     *
     * Default: [Dispatchers.IO]
     */
    fun dispatcher(dispatcher: CoroutineDispatcher) = apply {
        this.defaults = this.defaults.copy(dispatcher = dispatcher)
    }

    /**
     * Allow the use of [Bitmap.Config.HARDWARE].
     *
     * If false, any use of [Bitmap.Config.HARDWARE] will be treated as [Bitmap.Config.ARGB_8888].
     *
     * NOTE: Setting this to false this will reduce performance on Android O and above. Only disable if necessary.
     *
     * Default: true
     */
    fun allowHardware(enable: Boolean) = apply {
        this.defaults = this.defaults.copy(allowHardware = enable)
    }

    /**
     * Allow automatically using [Bitmap.Config.RGB_565] when an image is guaranteed to not have alpha.
     *
     * This will reduce the visual quality of the image, but will also reduce memory usage.
     *
     * Prefer only enabling this for low memory and resource constrained devices.
     *
     * Default: false
     */
    fun allowRgb565(enable: Boolean) = apply {
        this.defaults = this.defaults.copy(allowRgb565 = enable)
    }

    /**
     * Enable a crossfade animation with duration [CrossfadeDrawable.DEFAULT_DURATION] milliseconds when loading
     * images into an [ImageViewTarget].
     *
     * NOTE: Crossfading only applies to [ImageViewTarget]s.
     *
     * Default: false
     */
    fun crossfade(enable: Boolean) = apply {
        this.defaults = this.defaults.copy(crossfadeMillis = if (enable) CrossfadeDrawable.DEFAULT_DURATION else 0)
    }

    /**
     * Enable a crossfade animation with [durationMillis] milliseconds when loading images into an [ImageViewTarget].
     *
     * @see `crossfade(Boolean)`
     */
    fun crossfade(durationMillis: Int) = apply {
        require(durationMillis >= 0) { "Duration must be >= 0." }
        this.defaults = this.defaults.copy(crossfadeMillis = durationMillis)
    }

    /**
     * Set the default placeholder drawable to use when a request starts.
     */
    fun placeholder(@DrawableRes drawableResId: Int) = apply {
        this.defaults = this.defaults.copy(placeholder = context.getDrawableCompat(drawableResId))
    }

    /**
     * Set the default placeholder drawable to use when a request starts.
     */
    fun placeholder(drawable: Drawable?) = apply {
        this.defaults = this.defaults.copy(placeholder = drawable)
    }

    /**
     * Set the default error drawable to use when a request fails.
     */
    fun error(@DrawableRes drawableResId: Int) = apply {
        this.defaults = this.defaults.copy(error = context.getDrawableCompat(drawableResId))
    }

    /**
     * Set the default error drawable to use when a request fails.
     */
    fun error(drawable: Drawable?) = apply {
        this.defaults = this.defaults.copy(error = drawable)
    }

    /**
     * Create a new [ImageLoader] instance.
     */
    fun build(): ImageLoader {
        val availableMemorySize = Utils.calculateAvailableMemorySize(context, availableMemoryPercentage)
        val bitmapPoolSize = (bitmapPoolPercentage * availableMemorySize).toLong()
        val memoryCacheSize = (availableMemorySize - bitmapPoolSize).toInt()

        return RealImageLoader(
            context = context,
            defaults = defaults,
            bitmapPoolSize = bitmapPoolSize,
            memoryCacheSize = memoryCacheSize,
            okHttpClient = okHttpClient ?: buildDefaultOkHttpClient(),
            registry = registry ?: ComponentRegistry()
        )
    }

    private fun buildDefaultOkHttpClient(): OkHttpClient {
        // Create the default image disk cache.
        val cacheDirectory = Utils.getDefaultCacheDirectory(context)
        val cacheSize = Utils.calculateDiskCacheSize(cacheDirectory)
        val cache = Cache(cacheDirectory, cacheSize)

        // Don't limit the number of requests by host.
        val dispatcher = Dispatcher().apply {
            maxRequestsPerHost = maxRequests
        }

        return OkHttpClient.Builder()
            .cache(cache)
            .dispatcher(dispatcher)
            .apply { okHttpClientBuilder?.invoke(this) }
            .build()
    }
}
