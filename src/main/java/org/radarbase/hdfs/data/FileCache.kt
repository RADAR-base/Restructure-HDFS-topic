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

package org.radarbase.hdfs.data

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.Flushable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.avro.generic.GenericRecord
import org.radarbase.hdfs.FileStoreFactory
import org.radarbase.hdfs.accounting.Accountant
import org.radarbase.hdfs.util.Timer
import org.radarbase.hdfs.util.Timer.time
import org.slf4j.LoggerFactory

/** Keeps path handles of a path.  */
class FileCache(
        factory: FileStoreFactory,
        /** File that the cache is maintaining.  */
        val path: Path,
        /** Example record to create converter from, this is not written to path. */
        record: GenericRecord,
        /** Local temporary directory to store files in. */
        tmpDir: Path, private val accountant: Accountant
) : Closeable, Flushable, Comparable<FileCache> {

    private val writer: Writer
    private val recordConverter: RecordConverter
    private val storageDriver: StorageDriver = factory.storageDriver
    private val tmpPath: Path
    private val compression: Compression = factory.compression
    private val converterFactory: RecordConverterFactory = factory.recordConverter
    private val deduplicate: Boolean = factory.settings.isDeduplicate
    private val ledger: Accountant.Ledger = Accountant.Ledger()
    private val fileName: String = path.fileName.toString()
    private var lastUse: Long = 0
    private val hasError: AtomicBoolean = AtomicBoolean(false)

    init {
        val fileIsNew = !storageDriver.exists(path) || storageDriver.size(path) == 0L
        this.tmpPath = Files.createTempFile(tmpDir, fileName, ".tmp" + compression.extension)

        var outStream = compression.compress(fileName,
                BufferedOutputStream(Files.newOutputStream(tmpPath)))

        val inputStream: InputStream
        if (fileIsNew) {
            inputStream = ByteArrayInputStream(ByteArray(0))
        } else {
            inputStream = time("write.copyOriginal") {
                if (!copy(path, outStream, compression)) {
                    // restart output buffer
                    outStream.close()
                    // clear output file
                    outStream = compression.compress(
                            fileName, BufferedOutputStream(Files.newOutputStream(tmpPath)))
                }
                compression.decompress(storageDriver.newInputStream(path))
            }
        }

        this.writer = OutputStreamWriter(outStream)

        this.recordConverter = try {
            InputStreamReader(inputStream).use {
                reader -> converterFactory.converterFor(writer, record, fileIsNew, reader) }
        } catch (ex: IOException) {
            try {
                writer.close()
            } catch (exClose: IOException) {
                logger.error("Failed to close writer for {}", path, ex)
            }

            throw ex
        }
    }

    /**
     * Write a record to the cache.
     * @param record AVRO record
     * @return true or false based on [RecordConverter] write result
     * @throws IOException if the record cannot be used.
     */
    @Throws(IOException::class)
    fun writeRecord(record: GenericRecord, transaction: Accountant.Transaction): Boolean {
        val result = time("write.convert") { this.recordConverter.writeRecord(record) }
        lastUse = System.nanoTime()
        if (result) {
            ledger.add(transaction)
        }
        return result
    }

    fun markError() {
        this.hasError.set(true)
    }

    @Throws(IOException::class)
    override fun close() = time("close") {
        recordConverter.close()
        writer.close()

        if (!hasError.get()) {
            if (deduplicate) {
                time("close.deduplicate") {
                    converterFactory.deduplicate(fileName, tmpPath, tmpPath, compression)
                }
            }

            time("close.store") {
                storageDriver.store(tmpPath, path)
            }

            accountant.process(ledger)
        }
    }

    @Throws(IOException::class)
    override fun flush() = time("flush") {
        recordConverter.flush()
    }

    /**
     * Compares time that the filecaches were last used. If equal, it lexicographically compares
     * the absolute path of the path.
     * @param other FileCache to compare with.
     */
    override fun compareTo(other: FileCache): Int = comparator.compare(this, other)

    @Throws(IOException::class)
    private fun copy(source: Path, sink: OutputStream, compression: Compression): Boolean {
        return try {
            storageDriver.newInputStream(source).use { fileStream ->
                compression.decompress(fileStream).use { copyStream ->
                    copyStream.copyTo(sink, bufferSize = 8192)
                    true
                }
            }
        } catch (ex: IOException) {
            var corruptPath: Path? = null
            var suffix = ""
            var i = 0
            while (corruptPath == null && i < 100) {
                val path = source.resolveSibling(source.fileName.toString() + ".corrupted" + suffix)
                if (!storageDriver.exists(path)) {
                    corruptPath = path
                }
                suffix = "-$i"
                i++
            }
            if (corruptPath != null) {
                logger.error("Original file {} could not be read: {}." + " Moved to {}.", source, ex, corruptPath)
                storageDriver.move(source, corruptPath)
            } else {
                logger.error("Original file {} could not be read: {}." + " Too many corrupt backups stored, removing file.", source, ex)
            }
            false
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(FileCache::class.java)
        val comparator = compareBy(FileCache::lastUse, FileCache::path)
    }
}