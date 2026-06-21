package dev.tramai.vectorstore.pgvector

import com.fasterxml.jackson.databind.ObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tramai.vectorstore.SearchResult
import dev.tramai.vectorstore.VectorEntry
import dev.tramai.vectorstore.VectorStore
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.logging.Logger

/**
 * [VectorStore] implementation backed by PostgreSQL with the pgvector extension.
 *
 * Uses the `<->` operator for cosine distance search. Handles vector values as
 * PostgreSQL text representations (e.g., `[1,2,3]`) with `::vector` casting.
 *
 * **IMPORTANT:** The [dimensions] parameter must match the dimensionality of the
 * embedding model used to produce the vectors. A mismatch will cause pgvector
 * to reject INSERT or produce incorrect query results.
 *
 * **Note on connectionInitSql:** The `CREATE EXTENSION IF NOT EXISTS vector` command
 * runs on every new connection. This is intentionally cheap — PostgreSQL's
 * `IF NOT EXISTS` makes it a no-op after the first time the extension is installed.
 *
 * @param jdbcUrl      JDBC connection URL (e.g., jdbc:postgresql://localhost:5432/db).
 * @param user         Database user.
 * @param password     Database password.
 * @param tablePrefix  Optional prefix for table names (default: empty).
 * @param poolSize     Maximum connection pool size (default: 5).
 * @param dimensions   Dimensionality of the embedding vectors (default: 1536).
 * @param objectMapper Jackson mapper for JSON serialization/deserialization.
 */
class PgVectorStore(
    private val jdbcUrl: String,
    private val user: String,
    private val password: String,
    private val tablePrefix: String = "",
    private val poolSize: Int = 5,
    private val dimensions: Int = DEFAULT_DIMENSIONS,
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val ioDispatcher: CoroutineContext = kotlinx.coroutines.Dispatchers.IO,
) : VectorStore {

    private val logger: Logger = Logger.getLogger(PgVectorStore::class.java.name)

    private val dataSource: HikariDataSource by lazy {
        val config = HikariConfig().apply {
            this.jdbcUrl = this@PgVectorStore.jdbcUrl
            this.username = this@PgVectorStore.user
            this.password = this@PgVectorStore.password
            this.maximumPoolSize = this@PgVectorStore.poolSize
            this.driverClassName = "org.postgresql.Driver"
            this.connectionInitSql = "CREATE EXTENSION IF NOT EXISTS vector"
            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
        }
        HikariDataSource(config)
    }

    /** Cache of table names that have been verified to exist. */
    private val tableExistsCache = ConcurrentHashMap<String, Boolean>()

    override suspend fun upsert(collection: String, vectors: List<VectorEntry>) = withContext(ioDispatcher) {
        val tableName = tableNameFor(collection)
        try {
            dataSource.connection.use { conn ->
                ensureTable(conn, tableName)

                val autoCommit = conn.autoCommit
                conn.autoCommit = false
                try {
                    val sql = """
                    INSERT INTO $tableName (id, embedding, content, metadata)
                    VALUES (?, ?::vector, ?, ?::jsonb)
                    ON CONFLICT (id) DO UPDATE SET
                        embedding = EXCLUDED.embedding,
                        content = EXCLUDED.content,
                        metadata = EXCLUDED.metadata
                """.trimIndent()

                    conn.prepareStatement(sql).use { stmt ->
                        for (entry in vectors) {
                            stmt.setString(1, entry.id)
                            stmt.setString(2, vectorToText(entry.vector))
                            stmt.setString(3, entry.content)
                            stmt.setString(4, objectMapper.writeValueAsString(entry.metadata))
                            stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }
                    conn.commit()
                } catch (e: Exception) {
                    conn.rollback()
                    throw PgVectorException("PgVector upsert failed: ${e.message}", e)
                } finally {
                    conn.autoCommit = autoCommit
                }
            }
        } catch (e: PgVectorException) {
            throw e
        } catch (e: Exception) {
            throw PgVectorException("PgVector upsert failed: ${e.message}", e)
        }
        Unit
    }

    override suspend fun search(
        collection: String,
        query: FloatArray,
        topK: Int,
        filter: Map<String, String>?,
    ): List<SearchResult> = withContext(ioDispatcher) {
        val tableName = tableNameFor(collection)
        val vectorText = vectorToText(query)

        try {
            dataSource.connection.use { conn ->
                ensureTable(conn, tableName)

                val query = buildSearchQuery(tableName, filter, topK)

                conn.prepareStatement(query.sql).use { stmt ->
                    bindSearchParameters(stmt, vectorText, topK, filter)

                    stmt.executeQuery().use { rs ->
                        val results = mutableListOf<SearchResult>()
                        while (rs.next()) {
                            results.add(
                                SearchResult(
                                    id = rs.getString("id") ?: "",
                                    content = rs.getString("content") ?: "",
                                    metadata = parseMetadataJson(rs.getString("metadata")),
                                    score = rs.getDouble("similarity"),
                                )
                            )
                        }
                        results
                    }
                }
            }
        } catch (e: PgVectorException) {
            throw e
        } catch (e: Exception) {
            throw PgVectorException("PgVector search failed: ${e.message}", e)
        }
    }

    /**
     * Builds the SQL query for a pgvector similarity search with optional metadata filters.
     */
    private data class SearchQuery(val sql: String)

    /**
     * Binds the search parameters to a prepared statement for vector similarity search.
     */
    private fun bindSearchParameters(
        stmt: java.sql.PreparedStatement,
        vectorText: String,
        topK: Int,
        filter: Map<String, String>?,
    ) {
        var paramIdx = 1
        stmt.setString(paramIdx++, vectorText)
        if (filter != null) {
            for ((k, v) in filter) {
                stmt.setString(paramIdx++, k)
                stmt.setString(paramIdx++, v)
            }
        }
        stmt.setString(paramIdx++, vectorText)
        stmt.setInt(paramIdx, topK)
    }

    private fun buildSearchQuery(
        tableName: String,
        filter: Map<String, String>?,
        topK: Int,
    ): SearchQuery {
        val filterClause = if (!filter.isNullOrEmpty()) {
            val conditions = filter.entries.mapIndexed { i, _ ->
                "metadata->>? = ?"
            }
            "WHERE ${conditions.joinToString(" AND ")}"
        } else ""

        val sql = """
            SELECT id, content, metadata, 1 - (embedding <=> ?::vector) AS similarity
            FROM $tableName
            $filterClause
            ORDER BY embedding <=> ?::vector
            LIMIT ?
        """.trimIndent()
        return SearchQuery(sql)
    }

    override suspend fun delete(collection: String, ids: List<String>) = withContext(ioDispatcher) {
        val tableName = tableNameFor(collection)
        try {
            dataSource.connection.use { conn ->
                ensureTable(conn, tableName)
                val placeholders = ids.mapIndexed { i, _ -> "?" }.joinToString(", ")
                val sql = "DELETE FROM $tableName WHERE id IN ($placeholders)"

                conn.prepareStatement(sql).use { stmt ->
                    ids.forEachIndexed { index, id ->
                        stmt.setString(index + 1, id)
                    }
                    stmt.executeUpdate()
                }
            }
        } catch (e: PgVectorException) {
            throw e
        } catch (e: Exception) {
            throw PgVectorException("PgVector delete failed: ${e.message}", e)
        }
        Unit
    }

    override suspend fun listCollections(): List<String> = withContext(ioDispatcher) {
        try {
            dataSource.connection.use { conn ->
                val prefixPattern = "${tablePrefix}%"
                val escapedPrefix = Pattern.quote(tablePrefix)
                val sql = """
                SELECT DISTINCT regexp_replace(table_name, ?, '') AS collection_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name LIKE ?
                ORDER BY collection_name
            """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, "^$escapedPrefix")
                    stmt.setString(2, prefixPattern)
                    stmt.executeQuery().use { rs ->
                        val names = mutableListOf<String>()
                        while (rs.next()) {
                            names.add(rs.getString("collection_name"))
                        }
                        names
                    }
                }
            }
        } catch (e: PgVectorException) {
            throw e
        } catch (e: Exception) {
            throw PgVectorException("PgVector list collections failed: ${e.message}", e)
        }
    }

    private fun ensureTable(conn: Connection, tableName: String) {
        if (tableExistsCache.getOrDefault(tableName, false)) return
        val sql = """
            CREATE TABLE IF NOT EXISTS $tableName (
                id TEXT PRIMARY KEY,
                embedding vector($dimensions),
                content TEXT NOT NULL,
                metadata JSONB DEFAULT '{}'::jsonb
            )
        """.trimIndent()
        conn.createStatement().use { stmt ->
            stmt.execute(sql)
        }
        tableExistsCache[tableName] = true
    }

    private fun tableNameFor(collection: String): String {
        require(collection.isNotBlank()) { "Collection name must not be blank" }
        require(collection.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*$"))) {
            "Collection name must start with a letter and contain only alphanumeric characters and underscores"
        }
        return if (tablePrefix.isEmpty()) "vec_$collection" else "${tablePrefix}_$collection"
    }

    private fun vectorToText(vector: FloatArray): String {
        return vector.joinToString(prefix = "[", postfix = "]", separator = ",")
    }

    private fun parseMetadataJson(json: String?): Map<String, String> {
        if (json.isNullOrBlank() || json == "{}") return emptyMap()
        return try {
            val node = objectMapper.readTree(json)
            if (node.isObject) {
                node.fieldNames().asSequence().associateWith { name ->
                    node.get(name).asText("")
                }
            } else emptyMap()
        } catch (e: Exception) {
            logger.warning("Failed to parse metadata JSON: ${e.message}. JSON was: ${json.take(200)}")
            emptyMap()
        }
    }

    companion object {
        const val DEFAULT_DIMENSIONS: Int = 1536
    }
}
