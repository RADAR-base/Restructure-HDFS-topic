/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarbase.output

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.radarbase.output.accounting.OffsetRange
import org.radarbase.output.accounting.OffsetFilePersistence
import org.radarbase.output.accounting.OffsetPersistenceFactory
import org.radarbase.output.accounting.TopicPartition
import org.radarbase.output.config.LocalConfig
import org.radarbase.output.storage.LocalStorageDriver
import org.radarbase.output.storage.StorageDriver
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class OffsetRangeFileTest {
    private lateinit var testFile: Path
    private lateinit var storage: StorageDriver
    private lateinit var offsetPersistence: OffsetPersistenceFactory

    @BeforeEach
    @Throws(IOException::class)
    fun setUp(@TempDir dir: Path) {
        testFile = dir.resolve("test")
        Files.createFile(testFile)
        storage = LocalStorageDriver(LocalConfig())
        offsetPersistence = OffsetFilePersistence(storage)
    }

    @Test
    @Throws(IOException::class)
    fun readEmpty() {
        assertEquals(java.lang.Boolean.TRUE, offsetPersistence.read(testFile)?.isEmpty)

        storage.delete(testFile)

        // will not create
        assertNull(offsetPersistence.read(testFile))
    }

    @Test
    @Throws(IOException::class)
    fun write() {
        offsetPersistence.writer(testFile).use { rangeFile ->
            rangeFile.add(OffsetRange.parseFilename("a+0+0+1"))
            rangeFile.add(OffsetRange.parseFilename("a+0+1+2"))
        }

        val set = offsetPersistence.read(testFile)
        assertNotNull(set)
        requireNotNull(set)
        assertTrue(set.contains(OffsetRange.parseFilename("a+0+0+1")))
        assertTrue(set.contains(OffsetRange.parseFilename("a+0+1+2")))
        assertTrue(set.contains(OffsetRange.parseFilename("a+0+0+2")))
        assertFalse(set.contains(OffsetRange.parseFilename("a+0+0+3")))
        assertFalse(set.contains(OffsetRange.parseFilename("a+0+2+3")))
        assertFalse(set.contains(OffsetRange.parseFilename("a+1+0+1")))
        assertFalse(set.contains(OffsetRange.parseFilename("b+0+0+1")))
    }

    @Test
    @Throws(IOException::class)
    fun cleanUp() {
        offsetPersistence.writer(testFile).use { rangeFile ->
            rangeFile.add(OffsetRange.parseFilename("a+0+0+1"))
            rangeFile.add(OffsetRange.parseFilename("a+0+1+2"))
            rangeFile.add(OffsetRange.parseFilename("a+0+4+4"))
        }

        storage.newBufferedReader(testFile).use { br ->
            assertEquals(3, br.lines().count())
        }

        val rangeSet = offsetPersistence.read(testFile)
        assertEquals(2, rangeSet?.size(TopicPartition("a", 0)))
    }
}
