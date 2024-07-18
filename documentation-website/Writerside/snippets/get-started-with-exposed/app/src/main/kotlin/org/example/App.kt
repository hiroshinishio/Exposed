/*
 * This source file was generated by the Gradle 'init' task
 */
package org.example

import Tasks
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

fun main() {
    Database.connect("jdbc:h2:mem:test", driver = "org.h2.Driver")

    transaction {
        // print sql to std-out
        addLogger(StdOutSqlLogger)

        //...

        SchemaUtils.create (Tasks)

        val taskId = Tasks.insert {
            it[title] = "Learn Exposed"
            it[description] = "Go through the Get started with Exposed tutorial"
        } get Tasks.id

        val secondTaskId = Tasks.insert {
            it[title] = "Read The Hobbit"
            it[description] = "Read the first two chapters of The Hobbit"
            it[isCompleted] = true
        } get Tasks.id

        println("Created new tasks with ids $taskId and $secondTaskId.")

        Tasks.select(Tasks.id.count(), Tasks.isCompleted).groupBy(Tasks.isCompleted).forEach{
            println("${it[Tasks.isCompleted]}: ${it[Tasks.id.count()]} ")
        }

        //Update a task
        Tasks.update({ Tasks.id eq taskId }) {
            it[isCompleted] = true
        }

        val updatedTask = Tasks.select(Tasks.isCompleted).where(Tasks.id eq taskId).single()

        println("Updated task details: $updatedTask")

        //Delete a task
        Tasks.deleteWhere { id eq secondTaskId }

        println("Remaining tasks: ${Tasks.selectAll().toList()}")
    }
}