package org.radarbase.output.config

import com.azure.core.credential.BasicAuthenticationCredential
import com.azure.storage.blob.BlobServiceClient
import com.azure.storage.blob.BlobServiceClientBuilder
import com.azure.storage.common.StorageSharedKeyCredential
import com.fasterxml.jackson.annotation.JsonIgnore
import io.minio.MinioClient
import org.apache.hadoop.conf.Configuration
import org.radarbase.output.Application.Companion.CACHE_SIZE_DEFAULT
import org.radarbase.output.Plugin
import org.radarbase.output.compression.Compression
import org.radarbase.output.compression.CompressionFactory
import org.radarbase.output.format.FormatFactory
import org.radarbase.output.format.RecordConverterFactory
import org.radarbase.output.path.ObservationKeyPathFactory
import org.radarbase.output.path.RecordPathFactory
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class RestructureConfig(
        /** Whether and how to run as a service. */
        val service: ServiceConfig = ServiceConfig(enable = false),
        /** Cleaner of old files. */
        val cleaner: CleanerConfig = CleanerConfig(),
        /** Work limits. */
        val worker: WorkerConfig = WorkerConfig(),
        /** Topic exceptional handling. */
        val topics: Map<String, TopicConfig> = emptyMap(),
        /** Source data resource configuration. */
        val source: ResourceConfig = ResourceConfig("hdfs", hdfs = HdfsConfig()),
        /** Target data resource configration. */
        val target: ResourceConfig = ResourceConfig("local", local = LocalConfig()),
        /** Redis configuration for synchronization and storing offsets. */
        val redis: RedisConfig = RedisConfig(),
        /** Paths to use for processing. */
        val paths: PathConfig = PathConfig(),
        /** File compression to use for output files. */
        val compression: CompressionConfig = CompressionConfig(),
        /** File format to use for output files. */
        val format: FormatConfig = FormatConfig()) {

    fun validate() {
        source.validate()
        target.validate()
        cleaner.validate()
        service.validate()
        check(worker.enable || cleaner.enable) { "Either restructuring or cleaning needs to be enabled."}
    }

    /** Override configuration using command line arguments. */
    fun addArgs(args: CommandLineArgs) {
        args.asService?.let { copy(service = service.copy(enable = it)) }
        args.pollInterval?.let { copy(service = service.copy(interval = it)) }
        args.cacheSize?.let { copy(worker = worker.copy(cacheSize = it)) }
        args.numThreads?.let { copy(worker = worker.copy(numThreads = it)) }
        args.maxFilesPerTopic?.let { copy(worker = worker.copy(maxFilesPerTopic = it)) }
        args.tmpDir?.let { copy(paths = paths.copy(temp = Paths.get(it))) }
        args.inputPaths?.let { inputs -> copy(paths = paths.copy(inputs = inputs.map { Paths.get(it) })) }
        args.outputDirectory?.let { copy(paths = paths.copy(output = Paths.get(it))) }
        args.hdfsName?.let { copy(source = source.copy(hdfs = source.hdfs?.copy(nameNodes = listOf(it)) ?: HdfsConfig(nameNodes = listOf(it)))) }
        args.format?.let { copy(format = format.copy(type = it)) }
        args.deduplicate?.let { copy(format = format.copy(deduplication = format.deduplication.copy(enable = it))) }
        args.compression?.let { copy(compression = compression.copy(type = it)) }
        args.clean?.let { copy(cleaner = cleaner.copy(enable = it)) }
        args.noRestructure?.let { copy(worker = worker.copy(enable = !it)) }
    }

    companion object {
        fun load(path: String?): RestructureConfig = YAMLConfigLoader.load(path, RESTRUCTURE_CONFIG_FILE_NAME) {
            logger.info("No config file found. Using default configuration.")
            RestructureConfig()
        }

        private val logger = LoggerFactory.getLogger(RestructureConfig::class.java)
        internal const val RESTRUCTURE_CONFIG_FILE_NAME = "restructure.yml"
    }
}

/** Redis configuration. */
data class RedisConfig(
        /**
         * Full Redis URI. The protocol should be redis for plain text and rediss for TLS. It
         * should contain at least a hostname and port, but it may also include username and
         * password.
         */
        val uri: URI = URI.create("redis://localhost:6379"),
        /**
         * Prefix to use for creating a lock of a topic.
         */
        val lockPrefix: String = "radar-output/lock")

data class ServiceConfig(
        /** Whether to enable the service mode of this application. */
        val enable: Boolean,
        /** Polling interval in seconds. */
        val interval: Long = 300L,
        /** Age in days after an avro file can be removed. Ignored if not strictly positive. */
        val deleteAfterDays: Int = -1) {

    fun validate() {
        check(interval > 0) { "Cleaner interval must be strictly positive" }
    }
}

data class CleanerConfig(
        /** Whether to enable the cleaner. */
        val enable: Boolean = false,
        /** How often to run the cleaner in seconds. */
        val interval: Long = 1260L,
        /** Age in days after an avro file can be removed. Must be strictly positive. */
        val age: Int = 7) {

    fun validate() {
        check(age > 0) { "Cleaner file age must be strictly positive" }
        check(interval > 0) { "Cleaner interval must be strictly positive" }
    }
}

data class WorkerConfig(
        /** Whether to enable restructuring */
        val enable: Boolean = true,
        /** Number of threads to use for processing files. */
        val numThreads: Int = 1,
        /**
         * Maximum number of files to process for a given topic. Limit this to ensure that a single
         * processing iteration including lock takes a limited amount of time.
         */
        val maxFilesPerTopic: Int? = null,
        /**
         * Number of files to simultaneously keep in cache, including open writer. A higher size will
         * decrease overhead but increase memory usage and open file descriptors.
         */
        val cacheSize: Int = CACHE_SIZE_DEFAULT,
        /**
         * Number of offsets to simultaneously keep in cache. A higher size will
         * decrease overhead but increase memory usage.
         */
        val cacheOffsetsSize: Long = 500_000,
        /**
         * Minimum time since the file was last modified in seconds. Avoids
         * synchronization issues that may occur in a source file that is being
         * appended to.
         */
        val minimumFileAge: Long = 60
) {
    init {
        check(cacheSize >= 1) { "Maximum files per topic must be strictly positive" }
        maxFilesPerTopic?.let { check(it >= 1) { "Maximum files per topic must be strictly positive" } }
        check(numThreads >= 1) { "Number of threads should be at least 1" }
    }
}

interface PluginConfig {
    /** Factory class to use. */
    val factory: String
    /** Additional plugin-specific properties. */
    val properties: Map<String, String>
}

data class PathConfig(
        override val factory: String = ObservationKeyPathFactory::class.qualifiedName!!,
        override val properties: Map<String, String> = emptyMap(),
        /** Input paths referencing the source resource. */
        val inputs: List<Path> = emptyList(),
        /** Temporary directory for processing output files before uploading. */
        val temp: Path = Files.createTempDirectory("radar-output-restructure"),
        /** Output path on the target resource. */
        val output: Path = Paths.get("output")
) : PluginConfig {
    fun createFactory(): RecordPathFactory = factory.toPluginInstance(properties)
}

data class CompressionConfig(
        override val factory: String = CompressionFactory::class.qualifiedName!!,
        override val properties: Map<String, String> = emptyMap(),
        /** Compression type. Currently one of gzip, zip or none. */
        val type: String = "none"
) : PluginConfig {
    fun createFactory(): CompressionFactory = factory.toPluginInstance(properties)
    fun createCompression(): Compression = createFactory()[type]
}

data class FormatConfig(
        override val factory: String = FormatFactory::class.qualifiedName!!,
        override val properties: Map<String, String> = emptyMap(),
        /** Output format. One of csv or json. */
        val type: String = "csv",
        /** Whether and how to remove duplicate entries. */
        val deduplication: DeduplicationConfig = DeduplicationConfig(enable = false, distinctFields = emptySet(), ignoreFields = emptySet())
) : PluginConfig {
    fun createFactory(): FormatFactory = factory.toPluginInstance(properties)
    fun createConverter(): RecordConverterFactory = createFactory()[type]
}

private inline fun <reified T: Plugin> String.toPluginInstance(properties: Map<String, String>): T {
    return try {
        (Class.forName(this).getConstructor().newInstance() as T)
                .also { it.init(properties) }
    } catch (ex: ReflectiveOperationException) {
        throw IllegalStateException("Cannot map class $this to ${T::class.java.name}")
    }
}

data class TopicConfig(
        /** Topic-specific deduplication handling. */
        val deduplication: DeduplicationConfig? = null,
        /** Whether to exclude the topic from being processed. */
        val exclude: Boolean = false,
        /**
         * Whether to exclude the topic from being deleted, if this configuration has been set
         * in the service.
         */
        val excludeFromDelete: Boolean = false) {
    fun deduplication(deduplicationDefault: DeduplicationConfig): DeduplicationConfig {
        return deduplication
                ?.run { if (enable == null) copy(enable = deduplicationDefault.enable) else this }
                ?.run { if (distinctFields == null) copy(distinctFields = deduplicationDefault.distinctFields) else this }
                ?.run { if (ignoreFields == null) copy(distinctFields = deduplicationDefault.ignoreFields) else this }
                ?: deduplicationDefault
    }
}

data class DeduplicationConfig(
        /** Whether to enable deduplication. */
        val enable: Boolean? = null,
        /**
         * Only deduplicate using given fields. Fields not specified here are ignored
         * for determining duplication.
         */
        val distinctFields: Set<String>? = null,
        /**
         * Ignore given fields for determining whether a row is identical to another.
         */
        val ignoreFields: Set<String>? = null)

data class HdfsConfig(
        /** HDFS name nodes to use. */
        val nameNodes: List<String> = emptyList(),
        /** Additional HDFS configuration parameters. */
        val properties: Map<String, String> = emptyMap()) {

    val configuration: Configuration = Configuration()

    init {
        configuration["fs.hdfs.impl.disable.cache"] = "true"
        if (nameNodes.size == 1) {
            configuration["fs.defaultFS"] = "hdfs://${nameNodes.first()}:8020"
        }
        if (nameNodes.size >= 2) {
            val clusterId = "radarCluster"
            configuration["fs.defaultFS"] = "hdfs://$clusterId"
            configuration["dfs.nameservices"] = clusterId
            configuration["dfs.ha.namenodes.$clusterId"] = nameNodes.indices.joinToString(",") { "nn$it" }

            nameNodes.forEachIndexed { i, hostname ->
                configuration["dfs.namenode.rpc-address.$clusterId.nn$i"] = "$hostname:8020"
            }

            configuration["dfs.client.failover.proxy.provider.$clusterId"] = "org.apache.hadoop.hdfs.server.namenode.ha.ConfiguredFailoverProxyProvider"
        }
    }

    fun validate() {
        check(nameNodes.isNotEmpty()) { "Cannot use HDFS without any name nodes." }
    }
}

data class ResourceConfig(
        /** Resource type. One of s3, hdfs or local. */
        val type: String,
        val s3: S3Config? = null,
        val hdfs: HdfsConfig? = null,
        val local: LocalConfig? = null,
        val azure: AzureConfig? = null) {

    @JsonIgnore
    lateinit var sourceType: ResourceType

    fun validate() {
        sourceType = type.toResourceType()

        when(sourceType) {
            ResourceType.S3 -> checkNotNull(s3)
            ResourceType.HDFS -> checkNotNull(hdfs).also { it.validate() }
            ResourceType.LOCAL -> checkNotNull(local)
            ResourceType.AZURE -> checkNotNull(azure)
        }
    }
}

enum class ResourceType {
    S3, HDFS, LOCAL, AZURE
}

fun String.toResourceType() = when(toLowerCase()) {
    "s3" -> ResourceType.S3
    "hdfs" -> ResourceType.HDFS
    "local" -> ResourceType.LOCAL
    "azure" -> ResourceType.AZURE
    else -> throw IllegalArgumentException("Unknown resource type $this, choose s3, hdfs or local")
}

data class LocalConfig(
        /** User ID (uid) to write data as. Only valid on Unix-based filesystems. */
        val userId: Int = -1,
        /** Group ID (gid) to write data as. Only valid on Unix-based filesystems. */
        val groupId: Int = -1)

data class S3Config(
        /** URL to reach object store at. */
        val endpoint: String,
        /** Access token for writing data with. */
        val accessToken: String,
        /** Secret key belonging to access token. */
        val secretKey: String,
        /** Bucket name. */
        val bucket: String) {
    fun createS3Client() = MinioClient(endpoint, accessToken, secretKey)
}

data class AzureConfig(
        val endpoint: String,
        val container: String,
        val username: String?,
        val password: String?,
        val accountName: String?,
        val accountKey: String?,
        val sasToken: String?
) {
    fun createAzureClient(): BlobServiceClient = BlobServiceClientBuilder().apply {
        endpoint(endpoint)
        when {
            !username.isNullOrEmpty() && !password.isNullOrEmpty() -> credential(BasicAuthenticationCredential(username, password))
            !accountName.isNullOrEmpty() && !accountKey.isNullOrEmpty() -> credential(StorageSharedKeyCredential(accountName, accountKey))
            !sasToken.isNullOrEmpty() -> sasToken(sasToken)
            else -> logger.warn("No Azure credentials supplied. Assuming a public blob storage.")
        }
    }.buildClient()

    companion object {
        private val logger = LoggerFactory.getLogger(AzureConfig::class.java)
    }
}
