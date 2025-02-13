package coil

import android.content.ContentResolver
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import coil.api.get
import coil.api.getAny
import coil.api.load
import coil.api.loadAny
import coil.bitmappool.BitmapPool
import coil.decode.BitmapFactoryDecoder
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.Options
import coil.request.CachePolicy
import coil.resource.test.R
import coil.size.PixelSize
import coil.size.Size
import coil.util.Utils
import coil.util.createMockWebServer
import coil.util.getDrawableCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Cache
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [RealImageLoader].
 */
class RealImageLoaderIntegrationTest {

    companion object {
        private const val IMAGE_NAME = "normal.jpg"
        private const val IMAGE_SIZE = 443291L
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var server: MockWebServer
    private lateinit var imageLoader: ImageLoader

    @Before
    fun before() {
        server = createMockWebServer(context, IMAGE_NAME, IMAGE_NAME)
        imageLoader = ImageLoader(context)
    }

    @After
    fun after() {
        server.shutdown()
        imageLoader.shutdown()
    }

    // region Test all the supported data types.

    @Test
    fun string() {
        val data = server.url(IMAGE_NAME).toString()
        testLoad(data)
        testGet(data)
    }

    @Test
    fun httpUrl() {
        val data = server.url(IMAGE_NAME)
        testLoad(data)
        testGet(data)
    }

    @Test
    fun httpUri() {
        val data = Uri.parse(server.url(IMAGE_NAME).uri().toString())
        testLoad(data)
        testGet(data)
    }

    @Test
    fun resource() {
        val data = R.drawable.normal
        testLoad(data)
        testGet(data)
    }

    @Test
    fun resourceUri() {
        val data = Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${R.drawable.normal}")
        testLoad(data)
        testGet(data)
    }

    @Test
    fun file() {
        val data = copyNormalImageAssetToCacheDir()
        testLoad(data)
        testGet(data)
    }

    @Test
    fun fileUri() {
        val data = Uri.fromFile(copyNormalImageAssetToCacheDir())
        testLoad(data)
        testGet(data)
    }

    @Test
    fun drawable() {
        val data = context.getDrawableCompat(R.drawable.normal)
        val expectedSize = PixelSize(1080, 1350)
        testLoad(data, expectedSize)
        testGet(data, expectedSize)
    }

    @Test
    fun bitmap() {
        val data = (context.getDrawableCompat(R.drawable.normal) as BitmapDrawable).bitmap
        val expectedSize = PixelSize(1080, 1350)
        testLoad(data, expectedSize)
        testGet(data, expectedSize)
    }

    // endregion

    @Test
    fun unsupportedDataThrows() {
        val data = Any()
        assertFailsWith<IllegalStateException> { testLoad(data) }
        assertFailsWith<IllegalStateException> { testGet(data) }
    }

    @Test
    fun preloadWithMemoryCacheDisabledDoesNotDecode() {
        val imageLoader = ImageLoader(context) {
            componentRegistry {
                add(object : Decoder {
                    override fun handles(source: BufferedSource, mimeType: String?) = true

                    override suspend fun decode(
                        pool: BitmapPool,
                        source: BufferedSource,
                        size: Size,
                        options: Options
                    ) = throw IllegalStateException("Decode should not be called.")
                })
            }
        }

        val url = server.url(IMAGE_NAME)
        val cacheFolder = Utils.getDefaultCacheDirectory(context).apply {
            deleteRecursively()
            mkdirs()
        }

        assertTrue(cacheFolder.listFiles().isEmpty())

        runBlocking {
            suspendCancellableCoroutine<Unit> { continuation ->
                imageLoader.load(context, url) {
                    memoryCachePolicy(CachePolicy.DISABLED)
                    listener(
                        onSuccess = { _, _ -> continuation.resume(Unit) },
                        onError = { _, throwable -> continuation.resumeWithException(throwable) },
                        onCancel = { continuation.resumeWithException(CancellationException()) }
                    )
                }
            }
        }

        val cacheFile = cacheFolder.listFiles().find { it.name.contains(Cache.key(url)) && it.length() == IMAGE_SIZE }
        assertNotNull(cacheFile, "Did not find the image file in the disk cache.")
    }

    @Test
    fun getWithMemoryCacheDisabledDoesDecode() {
        var numDecodes = 0
        val imageLoader = ImageLoader(context) {
            componentRegistry {
                add(object : Decoder {
                    private val delegate = BitmapFactoryDecoder(context)

                    override fun handles(source: BufferedSource, mimeType: String?) = true

                    override suspend fun decode(
                        pool: BitmapPool,
                        source: BufferedSource,
                        size: Size,
                        options: Options
                    ): DecodeResult {
                        numDecodes++
                        return delegate.decode(pool, source, size, options)
                    }
                })
            }
        }

        val url = server.url(IMAGE_NAME)
        val cacheFolder = Utils.getDefaultCacheDirectory(context).apply {
            deleteRecursively()
            mkdirs()
        }

        assertTrue(cacheFolder.listFiles().isEmpty())

        runBlocking {
            imageLoader.get(url) {
                memoryCachePolicy(CachePolicy.DISABLED)
            }
        }

        val cacheFile = cacheFolder.listFiles().find { it.name.contains(Cache.key(url)) && it.length() == IMAGE_SIZE }
        assertNotNull(cacheFile, "Did not find the image file in the disk cache.")
        assertEquals(1, numDecodes)
    }

    private fun testLoad(data: Any, expectedSize: PixelSize = PixelSize(80, 100)) {
        val imageView = ImageView(context)
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER

        assertNull(imageView.drawable)

        runBlocking {
            suspendCancellableCoroutine<Unit> { continuation ->
                imageLoader.loadAny(context, data) {
                    target(imageView)
                    size(100, 100)
                    listener(
                        onSuccess = { _, _ -> continuation.resume(Unit) },
                        onError = { _, throwable -> continuation.resumeWithException(throwable) },
                        onCancel = { continuation.resumeWithException(CancellationException()) }
                    )
                }
            }
        }

        val drawable = imageView.drawable
        assertTrue(drawable is BitmapDrawable)
        assertEquals(expectedSize, drawable.bitmap.run { PixelSize(width, height) })
    }

    private fun testGet(data: Any, expectedSize: PixelSize = PixelSize(100, 125)) {
        val drawable = runBlocking {
            imageLoader.getAny(data) {
                size(100, 100)
            }
        }

        assertTrue(drawable is BitmapDrawable)
        assertEquals(expectedSize, drawable.bitmap.run { PixelSize(width, height) })
    }

    private fun copyNormalImageAssetToCacheDir(): File {
        return File(context.cacheDir, IMAGE_NAME).apply {
            sink().buffer().writeAll(context.assets.open(IMAGE_NAME).source())
        }
    }
}
