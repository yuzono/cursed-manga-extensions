@file:JvmName("OkHttpExtensionsKt")

package eu.kanade.tachiyomi.network

import okhttp3.Call
import okhttp3.Response
import rx.Observable
import rx.Producer
import rx.Subscription
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

// Match the host's blocking, backpressure-aware adapter; scheduling belongs to the source.
fun Call.asObservableSuccess(): Observable<Response> = Observable.unsafeCreate<Response> { subscriber ->
    val call = clone()
    val arbiter = object : Producer, Subscription {
        private val requested = AtomicBoolean()

        override fun request(n: Long) {
            if (n == 0L || !requested.compareAndSet(false, true)) return
            try {
                val response = call.execute()
                if (subscriber.isUnsubscribed) {
                    response.close()
                } else {
                    subscriber.onNext(response)
                    subscriber.onCompleted()
                }
            } catch (e: Exception) {
                if (!subscriber.isUnsubscribed) subscriber.onError(e)
            }
        }

        override fun unsubscribe() = call.cancel()
        override fun isUnsubscribed() = call.isCanceled()
    }
    subscriber.add(arbiter)
    subscriber.setProducer(arbiter)
}.doOnNext { response ->
    if (!response.isSuccessful) {
        response.close()
        throw IOException("HTTP ${response.code}")
    }
}
