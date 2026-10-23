/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hle.providers.applemusic.xposed

import com.juren233.hle.providers.applemusic.xposed.model.AppleSong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiskSongManagerTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("disk-song").toFile()
        DiskSongManager::class.java.getDeclaredField("baseDir").apply {
            isAccessible = true
            set(DiskSongManager, dir)
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `repeated loads return independent copies of the decoded song`() {
        DiskSongManager.save(AppleSong(adamId = "1", name = "disk"))

        val first = DiskSongManager.load("1")!!
        first.name = "mutated by metadata"
        val second = DiskSongManager.load("1")!!

        assertNotSame(first, second)
        assertEquals("disk", second.name)
    }

    @Test
    fun `save and delete invalidate the decoded cache`() {
        DiskSongManager.save(AppleSong(adamId = "2", name = "old"))
        assertEquals("old", DiskSongManager.load("2")?.name)

        DiskSongManager.save(AppleSong(adamId = "2", name = "new"))
        assertEquals("new", DiskSongManager.load("2")?.name)

        DiskSongManager.delete("2")
        assertNull(DiskSongManager.load("2"))
    }

    @Test
    fun `identical saves skip the write and keep the generation`() {
        val song = AppleSong(adamId = "3", name = "same")
        DiskSongManager.saveIfChanged(song)
        val generation = DiskSongManager.generation

        DiskSongManager.saveIfChanged(AppleSong(adamId = "3", name = "same"))
        assertEquals(generation, DiskSongManager.generation)

        DiskSongManager.saveIfChanged(AppleSong(adamId = "3", name = "changed"))
        assertEquals("changed", DiskSongManager.load("3")?.name)
        assertTrue(DiskSongManager.generation > generation)
    }

    @Test
    fun `saved song is served from memory and matches its disk round trip`() {
        val saved = AppleSong(adamId = "4", name = "fresh", duration = 123)
        DiskSongManager.save(saved)
        val diskFile = File(dir, "4.json.gz")
        val bytes = diskFile.readBytes()

        diskFile.delete()
        assertEquals(saved, DiskSongManager.load("4"))

        diskFile.writeBytes(bytes)
        DiskSongManager.delete("4")
        diskFile.writeBytes(bytes)
        assertEquals(saved, DiskSongManager.load("4"))
    }
}
