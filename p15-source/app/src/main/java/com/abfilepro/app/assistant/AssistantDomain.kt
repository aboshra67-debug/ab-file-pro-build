package com.abfilepro.app.assistant

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object AssistantDomain {
    fun modulesFor(category: AssistantCategory): List<AssistantModule> =
        if (category == AssistantCategory.ALL) AssistantModule.entries
        else AssistantModule.entries.filter { category in it.categories }

    fun summary(entries: List<AssistantEntry>, nowMillis: Long = System.currentTimeMillis()): DailySummary {
        val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        fun isToday(value: Long?): Boolean = value?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() == today
        } ?: false
        return DailySummary(
            openTasks = entries.count { it.module == AssistantModule.TASKS && !it.completed },
            appointmentsToday = entries.count { it.module == AssistantModule.CALENDAR && isToday(it.dueAt) },
            pendingFamilyRequests = entries.count { it.module == AssistantModule.FAMILY_REQUESTS && !it.completed },
            activeMedicationReminders = entries.count { it.module == AssistantModule.HEALTH && it.enabled },
        )
    }

    fun sanitizeCategory(module: AssistantModule, requested: AssistantCategory): AssistantCategory = when {
        requested in module.categories -> requested
        AssistantCategory.PERSONAL in module.categories -> AssistantCategory.PERSONAL
        else -> AssistantCategory.WORK
    }

    /** Small bounded arithmetic parser; it never evaluates code or accesses reflection. */
    fun evaluateExpression(expression: String): Double {
        require(expression.length <= 120) { "Expression is too long" }
        require(expression.all { it.isDigit() || it.isWhitespace() || it in ".+-*/()%" }) {
            "Unsupported character"
        }
        return ExpressionParser(expression).parse()
    }

    private class ExpressionParser(private val source: String) {
        private var index = 0

        fun parse(): Double {
            val value = expression()
            skipSpaces()
            require(index == source.length) { "Unexpected input" }
            require(value.isFinite()) { "Invalid result" }
            return value
        }

        private fun expression(): Double {
            var value = term()
            while (true) {
                skipSpaces()
                value = when {
                    take('+') -> value + term()
                    take('-') -> value - term()
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = unary()
            while (true) {
                skipSpaces()
                value = when {
                    take('*') -> value * unary()
                    take('/') -> value / unary()
                    take('%') -> value % unary()
                    else -> return value
                }
            }
        }

        private fun unary(): Double {
            skipSpaces()
            return when {
                take('+') -> unary()
                take('-') -> -unary()
                else -> primary()
            }
        }

        private fun primary(): Double {
            skipSpaces()
            if (take('(')) {
                val value = expression()
                skipSpaces()
                require(take(')')) { "Missing closing parenthesis" }
                return value
            }
            val start = index
            var dots = 0
            while (index < source.length && (source[index].isDigit() || source[index] == '.')) {
                if (source[index] == '.') dots++
                index++
            }
            require(index > start && dots <= 1) { "Number expected" }
            return source.substring(start, index).toDouble()
        }

        private fun skipSpaces() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        private fun take(char: Char): Boolean {
            if (index < source.length && source[index] == char) {
                index++
                return true
            }
            return false
        }
    }
}
