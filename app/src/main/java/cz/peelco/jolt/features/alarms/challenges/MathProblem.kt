package cz.peelco.jolt.features.alarms.challenges

import kotlin.random.Random

/**
 * A small mental-arithmetic problem with three answers to pick from: one
 * thumb, half asleep. Distractors are plausible slips (a neighbouring times
 * table row, or ±1…3), so guessing without reading usually fails.
 */
data class MathProblem(
    val firstOperand: Int,
    val secondOperand: Int,
    val operation: Operation,
    /** Exactly three distinct values, one the answer, in random order. */
    val choices: List<Int>,
) {
    enum class Operation(
        val symbol: String,
        val spokenName: String,
    ) {
        ADD("+", "plus"),
        SUBTRACT("−", "minus"),
        MULTIPLY("×", "times"),
        ;

        fun apply(
            lhs: Int,
            rhs: Int,
        ): Int =
            when (this) {
                ADD -> lhs + rhs
                SUBTRACT -> lhs - rhs
                MULTIPLY -> lhs * rhs
            }
    }

    val answer: Int get() = operation.apply(firstOperand, secondOperand)
    val question: String get() = "$firstOperand ${operation.symbol} $secondOperand"
    val spokenQuestion: String get() = "$firstOperand ${operation.spokenName} $secondOperand"

    companion object {
        fun of(
            firstOperand: Int,
            secondOperand: Int,
            operation: Operation,
            random: Random = Random.Default,
        ): MathProblem {
            val answer = operation.apply(firstOperand, secondOperand)
            val step = if (operation == Operation.MULTIPLY) maxOf(1, firstOperand) else 1
            return MathProblem(firstOperand, secondOperand, operation, choices(answer, step, random))
        }

        /** Operands 2…12; subtraction never goes negative. */
        fun random(random: Random = Random.Default): MathProblem {
            val operation = Operation.entries.random(random)
            val first = random.nextInt(2, 13)
            val second = random.nextInt(2, 13)
            return of(
                firstOperand = maxOf(first, second),
                secondOperand = if (operation == Operation.SUBTRACT) minOf(first, second) else second,
                operation = operation,
                random = random,
            )
        }

        private fun choices(
            answer: Int,
            step: Int,
            random: Random,
        ): List<Int> {
            val candidates = listOf(-2, -1, 1, 2).map { answer + it * step } + listOf(-3, -2, -1, 1, 2, 3).map { answer + it }
            val distractors = mutableListOf<Int>()
            for (candidate in candidates.shuffled(random)) {
                if (candidate >= 0 && candidate != answer && candidate !in distractors) distractors += candidate
                if (distractors.size == 2) break
            }
            return (listOf(answer) + distractors).shuffled(random)
        }
    }
}
