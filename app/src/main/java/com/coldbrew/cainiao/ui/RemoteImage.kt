package com.coldbrew.cainiao.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 极简网络图片加载：内存 LruCache + 4 线程池 + 视图 tag 防错位。
 *
 * 工程刻意不引第三方依赖（整个 APK 只有 JDK + AndroidX），所以不上 Glide/Coil。
 * 首页商品缩略图尺寸很小（阿里 CDN 的 210x210 / 48x48），这套足够。
 */
object RemoteImage {

    private val pool = Executors.newFixedThreadPool(4)

    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 正在下载的 key，避免同一张图并发拉多次 */
    private val inflight = ConcurrentHashMap.newKeySet<String>()

    fun load(iv: ImageView, url: String?) {
        if (url.isNullOrBlank()) {
            iv.setImageDrawable(null)
            return
        }
        // 归一化：CDN 可能给相对路径，官方 getPackageImageUrl 里就是这么补的
        val full = when {
            url.length > 4 && url.substring(0, 4).equals("http", true) -> url
            url.startsWith("//") -> "https:$url"
            else -> "https://gw2.alicdn.com/i1/$url"
        }

        val hit = cache.get(full)
        if (hit != null) {
            iv.setImageBitmap(hit)
            return
        }

        iv.tag = full
        iv.setImageDrawable(null)
        if (!inflight.add(full)) return

        pool.execute {
            val bmp = download(full)
            inflight.remove(full)
            if (bmp != null) cache.put(full, bmp)
            iv.post {
                // 视图可能已被复用去显示别的图
                if (iv.tag == full && bmp != null) iv.setImageBitmap(bmp)
            }
        }
    }

    private fun download(url: String): Bitmap? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) Chrome/122.0.0.0")
            val stream: InputStream = conn.inputStream
            stream.use { BitmapFactory.decodeStream(it) }
        } finally {
            runCatching { conn.disconnect() }
        }
    } catch (_: Exception) {
        null
    }
}
