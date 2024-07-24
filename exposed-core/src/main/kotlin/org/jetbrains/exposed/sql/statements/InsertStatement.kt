package org.jetbrains.exposed.sql.statements

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.vendors.*
import org.jetbrains.exposed.sql.vendors.inProperCase
import java.sql.ResultSet
import java.sql.SQLException
import kotlin.properties.Delegates

/**
 * Represents the SQL statement that inserts a new row into a table.
 *
 * @param table Table to insert the new row into.
 * @param isIgnore Whether to ignore errors or not.
 * **Note** [isIgnore] is not supported by all vendors. Please check the documentation.
 */
open class InsertStatement<Key : Any>(
    val table: Table,
    val isIgnore: Boolean = false
) : UpdateBuilder<Int>(StatementType.INSERT, listOf(table)) {

    /**
     * The number of rows affected by the insert operation.
     *
     * When returned by a `BatchInsertStatement` or `BatchUpsertStatement`, the returned value is calculated using the
     * sum of the individual values generated by each statement.
     *
     * **Note**: Some vendors support returning the affected-row value of 2 if an existing row is updated by an upsert
     * operation; please check the documentation.
     */
    var insertedCount: Int by Delegates.notNull()

    /** The [ResultRow]s generated by processing the database result set retrieved after executing the statement. */
    var resultedValues: List<ResultRow>? = null
        private set

    infix operator fun <T> get(column: Column<T>): T {
        val row = resultedValues?.firstOrNull() ?: error("No key generated")
        return row[column]
    }

    infix operator fun <T> get(column: CompositeColumn<T>): T {
        val row = resultedValues?.firstOrNull() ?: error("No key generated")
        return row[column]
    }

    /**
     * Returns the value of a given [column] from the first stored [ResultRow], or `null` if either no results were
     * retrieved from the database or if the column cannot be found in the row.
     */
    fun <T> getOrNull(column: Column<T>): T? = resultedValues?.firstOrNull()?.getOrNull(column)

    private fun processResults(rs: ResultSet?, inserted: Int): List<ResultRow> {
        // All the nullable columns except those that have configured default value
        val defaultNulls = table.columns.filter {
            it.columnType.nullable && it.dbDefaultValue == null && it.defaultValueFun == null && !it.isDatabaseGenerated
        }.associateWith { null }

        val allResultSetValues = rs?.returnedValues(inserted)

        @Suppress("UNCHECKED_CAST")
        return arguments!!
            .mapIndexed { itemIndex, columnValues ->
                val resultSetValues = allResultSetValues?.getOrNull(itemIndex) ?: hashMapOf()
                val argumentValues = columnValues.toMap()
                    .filterValues { it != DefaultValueMarker }
                    // If the value is preferred to be taken from the result set it should not be taken from arguments
                    // but only if it's present in result set
                    .filter { (column, value) -> !(isColumnValuePreferredFromResultSet(column, value) && column in resultSetValues) }
                defaultNulls + resultSetValues + argumentValues
            }
            .map { ResultRow.createAndFillValues(it as Map<Expression<*>, Any?>) }
    }

    @Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
    private fun ResultSet.returnedValues(inserted: Int): ArrayList<MutableMap<Column<*>, Any?>> {
        val resultSetsValues = arrayListOf<MutableMap<Column<*>, Any?>>()

        if (inserted > 0) {
            val columnIndexesInResultSet = returnedColumns()

            val firstAutoIncColumn = autoIncColumns.firstOrNull()
            if (firstAutoIncColumn != null || columnIndexesInResultSet.isNotEmpty()) {
                while (next() == true) {
                    try {
                        val returnedValues = columnIndexesInResultSet.associateTo(mutableMapOf()) { it.first to getObject(it.second) }
                        if (returnedValues.isEmpty() && firstAutoIncColumn != null) {
                            returnedValues[firstAutoIncColumn] = getObject(1)
                        }
                        resultSetsValues.add(returnedValues)
                    } catch (cause: ArrayIndexOutOfBoundsException) {
                        // EXPOSED-191 Flaky Oracle test on TC build
                        // this try/catch should help to get information about the flaky test.
                        // try/catch can be safely removed after the fixing the issue.
                        // TooGenericExceptionCaught suppress also can be removed

                        val preparedSql = prepareSQL(TransactionManager.current(), prepared = true)

                        val returnedColumnsString = columnIndexesInResultSet
                            .mapIndexed { index, pair -> "column: ${pair.first.name}, index: ${pair.second} (columns-list-index: $index)" }
                            .joinToString(prefix = "[", postfix = "]", separator = ", ")

                        exposedLogger.error(
                            "ArrayIndexOutOfBoundsException on processResults. " +
                                "Table: ${table.tableName}, firstAutoIncColumn: ${firstAutoIncColumn?.name}, " +
                                "inserted: $inserted, returnedColumnsString: $returnedColumnsString. " +
                                "Failed SQL: $preparedSql",
                            cause
                        )
                        throw cause
                    }
                }

                if (inserted > 1 && firstAutoIncColumn != null && resultSetsValues.isNotEmpty() && !currentDialect.supportsMultipleGeneratedKeys) {
                    // H2/SQLite only returns one last generated key...
                    (resultSetsValues[0][firstAutoIncColumn] as? Number)?.toLong()?.let {
                        var id = it

                        while (resultSetsValues.size < inserted) {
                            id -= 1
                            resultSetsValues.add(0, mutableMapOf(firstAutoIncColumn to id))
                        }
                    }
                }

                assert(
                    isIgnore || resultSetsValues.isEmpty() || resultSetsValues.size == inserted ||
                        currentDialect.supportsTernaryAffectedRowValues
                ) {
                    "Number of autoincs (${resultSetsValues.size}) doesn't match number of batch entries ($inserted)"
                }
            }
        }

        return resultSetsValues
    }

    /**
     * Returns indexes of the table columns in [ResultSet]
     */
    private fun ResultSet?.returnedColumns() = (if (currentDialect.supportsOnlyIdentifiersInGeneratedKeys) autoIncColumns else table.columns).mapNotNull { col ->
        @Suppress("SwallowedException")
        try {
            this?.findColumn(col.name)?.let { col to it }
        } catch (e: SQLException) {
            null
        }
    }

    protected open fun isColumnValuePreferredFromResultSet(column: Column<*>, value: Any?): Boolean {
        return column.columnType.isAutoInc || value is NextVal<*>
    }

    @Suppress("NestedBlockDepth")
    @Deprecated(
        "This function is used in derived classes to build a list of arguments. " +
            "It's recommended to avoid including all default and nullable values in insert statements, " +
            "as these values can often be generated automatically by the database."
    )
    protected open fun valuesAndDefaults(values: Map<Column<*>, Any?> = this.values): Map<Column<*>, Any?> {
        val result = values.toMutableMap()
        targets.forEach { table ->
            table.columns.forEach { column ->
                if ((column.dbDefaultValue != null || column.defaultValueFun != null) && column !in values.keys) {
                    val value = when {
                        column.defaultValueFun != null -> column.defaultValueFun!!()
                        else -> DefaultValueMarker
                    }
                    result[column] = value
                }
            }
        }
        return result
    }

    protected open fun valuesAndClientDefaults(values: Map<Column<*>, Any?> = this.values): Map<Column<*>, Any?> {
        val clientDefaultValues = targets.flatMap {
            it.columns
                // Take clientDefault columns
                .filter { column -> column.dbDefaultValue == null && column.defaultValueFun != null && column !in values.keys }
                .map { column -> column to column.defaultValueFun!!() }
        }

        return clientDefaultValues.toMap() + values
    }

    override fun prepareSQL(transaction: Transaction, prepared: Boolean): String {
        val values = arguments!!.first()
        val sql = values.toSqlString(prepared)
        return transaction.db.dialect.functionProvider
            .insert(isIgnore, table, values.map { it.first }, sql, transaction)
    }

    protected fun List<Pair<Column<*>, Any?>>.toSqlString(prepared: Boolean): String {
        val builder = QueryBuilder(prepared)
        return if (isEmpty()) {
            ""
        } else {
            with(builder) {
                this@toSqlString.appendTo(prefix = "VALUES (", postfix = ")") { (column, value) ->
                    registerArgument(column, value)
                }
                toString()
            }
        }
    }

    protected open fun PreparedStatementApi.execInsertFunction(): Pair<Int, ResultSet?> {
        val inserted = if (arguments().count() > 1 || isAlwaysBatch) executeBatch().sum() else executeUpdate()
        // According to the `processResults()` method when supportsOnlyIdentifiersInGeneratedKeys is false
        // all the columns could be taken from result set
        val rs = if (columnsGeneratedOnDB().isNotEmpty() || !currentDialect.supportsOnlyIdentifiersInGeneratedKeys) {
            resultSet
        } else {
            null
        }
        return inserted to rs
    }

    override fun PreparedStatementApi.executeInternal(transaction: Transaction): Int {
        val (inserted, rs) = execInsertFunction()
        return inserted.apply {
            insertedCount = this
            resultedValues = processResults(rs, this)
        }
    }

    protected val autoIncColumns: List<Column<*>>
        get() {
            val nextValExpressionColumns = values.filterValues { it is NextVal<*> }.keys
            return targets.flatMap { it.columns }.filter { column ->
                when {
                    column.autoIncColumnType?.nextValExpression != null -> currentDialect.supportsSequenceAsGeneratedKeys
                    column.columnType.isAutoInc -> true
                    column in nextValExpressionColumns -> currentDialect.supportsSequenceAsGeneratedKeys
                    else -> false
                }
            }
        }

    override fun prepared(transaction: Transaction, sql: String): PreparedStatementApi = when {
        // https://github.com/pgjdbc/pgjdbc/issues/1168
        // Column names always escaped/quoted in RETURNING clause
        columnsGeneratedOnDB().isNotEmpty() && currentDialect is PostgreSQLDialect ->
            transaction.connection.prepareStatement(sql, true)

        autoIncColumns.isNotEmpty() ->
            // http://viralpatel.net/blogs/oracle-java-jdbc-get-primary-key-insert-sql/
            transaction.connection.prepareStatement(sql, autoIncColumns.map { it.name.inProperCase() }.toTypedArray())

        else -> transaction.connection.prepareStatement(sql, false)
    }

    open var arguments: List<List<Pair<Column<*>, Any?>>>? = null
        get() = field ?: run {
            listOf(valuesAndClientDefaults().toList())
                .apply { field = this }
        }

    override fun arguments(): List<Iterable<Pair<IColumnType<*>, Any?>>> {
        return arguments?.map { args ->
            val builder = QueryBuilder(true)
            args.filter { (_, value) ->
                value != DefaultValueMarker
            }.forEach { (column, value) ->
                builder.registerArgument(column, value)
            }
            builder.args
        } ?: emptyList()
    }

    protected fun isEntityIdClientSideGeneratedUUID(column: Column<*>) =
        (column.columnType as? EntityIDColumnType<*>)
            ?.idColumn
            ?.takeIf { it.columnType is UUIDColumnType }
            ?.defaultValueFun != null

    /**
     * Returns the list of columns with default values that can not be taken locally.
     * It is the columns defined with `defaultExpression()`, `databaseGenerated()`
     */
    private fun columnsWithDatabaseDefaults() = targets.flatMap { it.columns }.filter { it.defaultValueFun == null && it.dbDefaultValue != null }

    /**
     * Returns all the columns for which value can not be derived without actual request.
     *
     * At the current moment it is the auto increment columns and columns with database side generated defaults
     */
    private fun columnsGeneratedOnDB(): Collection<Column<*>> = (autoIncColumns + columnsWithDatabaseDefaults()).toSet()
}
