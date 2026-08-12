/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.spotify

import io.reactivex.rxjava3.core.Single
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import p.kg80

class SpotifyLyricsClientRequesterTest {
    @Test
    fun `invokes exact kg80 request and observes success through target RxJava`() {
        val value = Any()
        val client = FakeClient(Single.just(value))
        val successes = mutableListOf<Any>()

        SpotifyLyricsClientRequester.start(
            client = client,
            trackUri = TRACK,
            onSuccess = successes::add,
            onError = { error -> throw AssertionError(error) },
        )

        assertEquals(listOf(TRACK to null), client.calls)
        assertEquals(1, successes.size)
        assertSame(value, successes.single())
    }

    @Test
    fun `forwards target RxJava error`() {
        val failure = IllegalStateException("network")
        val client = FakeClient(Single.error(failure))
        val errors = mutableListOf<Throwable>()

        SpotifyLyricsClientRequester.start(
            client = client,
            trackUri = TRACK,
            onSuccess = { error("unexpected success") },
            onError = errors::add,
        )

        assertEquals(listOf(failure), errors)
    }

    @Test
    fun `cancellation disposes returned target subscription`() {
        val response = Single.just<Any>(Any())
        val client = FakeClient(response)
        val cancellation = SpotifyLyricsClientRequester.start(
            client = client,
            trackUri = TRACK,
            onSuccess = {},
            onError = { error -> throw AssertionError(error) },
        )

        cancellation.cancel()
        cancellation.cancel()

        assertTrue(requireNotNull(response.lastDisposable).isDisposed)
    }

    private class FakeClient(
        private val response: Single<Any>,
    ) : kg80 {
        val calls = mutableListOf<Pair<String, String?>>()

        override fun b(trackUri: String, language: String?): Single<Any> {
            calls += trackUri to language
            return response
        }
    }

    private companion object {
        const val TRACK = "spotify:track:AAAAAAAAAAAAAAAAAAAAAA"
    }
}
