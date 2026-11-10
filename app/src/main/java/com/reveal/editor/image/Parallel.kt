package com.reveal.editor.image

/**
 * 行区间并行：把 [0,h) 切成若干段交给线程池，回调只拿区间，不涉及 lambda 里的 return，
 * 所以任何带早退的函数都能安全并行（这是 0.74.33 事故后定下的正确做法）。
 */
internal object Parallel {

    private val pool: java.util.concurrent.ExecutorService by lazy {
        val n = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        java.util.concurrent.Executors.newFixedThreadPool(n)
    }

    fun chunks(h: Int, fn: (Int, Int) -> Unit) {
        if (h <= 0) return
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        if (h < 64 || threads <= 1) { fn(0, h); return }
        val band = (h + threads - 1) / threads
        val tasks = ArrayList<java.util.concurrent.Future<*>>(threads)
        var y0 = 0
        while (y0 < h) {
            val a = y0
            val b = minOf(h, y0 + band)
            tasks.add(pool.submit { fn(a, b) })
            y0 = b
        }
        for (t in tasks) {
            try { t.get() } catch (e: Exception) { throw RuntimeException(e) }
        }
    }
}
