package org.radarbase.output.cleaner

import org.apache.avro.generic.GenericRecord
import org.radarbase.output.FileStoreFactory
import org.radarbase.output.source.SourceStorage
import org.radarbase.output.source.TopicFile
import org.radarbase.output.worker.RestructureWorker
import org.slf4j.LoggerFactory
import java.io.IOException

class TimestampExtractionCheck(
        sourceStorage: SourceStorage,
        fileStoreFactory: FileStoreFactory
) : ExtractionCheck {
    private val cacheStore = TimestampFileCacheStore(fileStoreFactory)
    private val reader = sourceStorage.createReader()
    private val pathFactory = fileStoreFactory.pathFactory
    private val batchSize = fileStoreFactory.config.worker.cacheOffsetsSize

    private var cachedRecords = 0L

    override fun isExtracted(file: TopicFile): Boolean {
        val result = reader.newInput(file).use { input ->
            // processing zero-length files may trigger a stall. See:
            // https://github.com/RADAR-base/Restructure-HDFS-topic/issues/3
            if (input.length() == 0L) {
                logger.warn("File {} has zero length, skipping.", file.path)
                return false
            }
            RestructureWorker.extractRecords(input) { records ->
                records
                        .mapIndexed { i, record ->
                            cachedRecords += 1L
                            containsRecord(file, file.range.range.from + i.toLong(), record)
                        }
                        .all { it }
            }
        }
        if (cachedRecords > batchSize) {
            cachedRecords = 0L
            cacheStore.clear()
        }
        return result
    }

    override fun close() {
        reader.close()
    }

    private fun containsRecord(topicFile: TopicFile, offset: Long, record: GenericRecord): Boolean {
        var suffix = 0

        do {
            val (path) = pathFactory.getRecordOrganization(
                    topicFile.topic, record, suffix)

            try {
                when (cacheStore.contains(path, record)) {
                    TimestampFileCacheStore.FindResult.FILE_NOT_FOUND -> {
                        logger.warn("Target {} for record of {} (offset {}) has not been created yet.", path, topicFile.path, offset)
                        return false
                    }
                    TimestampFileCacheStore.FindResult.NOT_FOUND -> {
                        logger.warn("Target {} does not contain record of {} (offset {})", path, topicFile.path, offset)
                        return false
                    }
                    TimestampFileCacheStore.FindResult.FOUND -> return true
                    TimestampFileCacheStore.FindResult.BAD_SCHEMA -> {
                        logger.debug("Schema of {} does not match schema of {} (offset {})", path, topicFile.path, offset)
                        suffix += 1 // continue next suffix
                    }
                }
            } catch (ex: IOException) {
                logger.error("Failed to read target file {} for checking data integrity", path, ex)
                return false
            }
        } while (true)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TimestampExtractionCheck::class.java)
    }
}
