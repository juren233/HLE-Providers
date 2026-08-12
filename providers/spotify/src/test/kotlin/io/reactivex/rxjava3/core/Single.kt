package io.reactivex.rxjava3.core

import io.reactivex.rxjava3.functions.Consumer

class Single<T> private constructor(
    private val value: T,
    private val successObservers: List<Consumer<in T>>,
) {
    fun doOnSuccess(observer: Consumer<in T>): Single<T> =
        Single(value, successObservers + observer)

    fun blockingGet(): T {
        successObservers.forEach { observer -> observer.accept(value) }
        return value
    }

    companion object {
        fun <T> just(value: T): Single<T> = Single(value, emptyList())
    }
}
