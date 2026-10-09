package cz.peelco.jolt.features

import com.google.common.truth.Truth.assertThat
import cz.peelco.jolt.domain.model.Alarm
import cz.peelco.jolt.domain.model.AlarmLocation
import cz.peelco.jolt.domain.model.DismissChallenge
import cz.peelco.jolt.features.alarms.AlarmScheduler
import cz.peelco.jolt.features.alarms.challenges.JumpCounter
import cz.peelco.jolt.features.alarms.challenges.MathProblem
import cz.peelco.jolt.features.alarms.switchableChallenges
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

class MathProblemTest {
    @Test
    fun choicesAreThreeDistinctNonNegativeValuesIncludingTheAnswer() {
        val random = Random(42)
        repeat(500) {
            val problem = MathProblem.random(random)
            assertThat(problem.choices).hasSize(3)
            assertThat(problem.choices.toSet()).hasSize(3)
            assertThat(problem.choices).contains(problem.answer)
            assertThat(problem.choices.all { it >= 0 }).isTrue()
            assertThat(problem.firstOperand).isIn(2..12)
            assertThat(problem.secondOperand).isIn(2..12)
            assertThat(problem.answer).isAtLeast(0)
        }
    }

    @Test
    fun multiplicationDistractorsCanBeANeighbouringTimesTableRow() {
        val problem = MathProblem.of(7, 8, MathProblem.Operation.MULTIPLY, Random(1))
        assertThat(problem.answer).isEqualTo(56)
        assertThat(problem.question).isEqualTo("7 × 8")
        assertThat(problem.spokenQuestion).isEqualTo("7 times 8")
        val plausible = setOf(42, 49, 63, 70, 53, 54, 55, 57, 58, 59)
        assertThat(plausible).containsAtLeastElementsIn(problem.choices.filter { it != 56 })
    }
}

class JumpCounterTest {
    @Test
    fun countsPeaksAboveTheThresholdAtMostOncePerInterval() {
        val counter = JumpCounter()
        assertThat(counter.onSample(0f, 0f, 1f, 0)).isFalse()
        assertThat(counter.onSample(0f, 0f, 2f, 100)).isTrue()
        assertThat(counter.onSample(0f, 0f, 2.5f, 400)).isFalse()
        assertThat(counter.onSample(1.5f, 1.5f, 0f, 800)).isTrue()
        assertThat(counter.count).isEqualTo(2)
    }

    @Test
    fun stopsAtTen() {
        val counter = JumpCounter()
        repeat(15) { counter.onSample(0f, 0f, 3f, it * 1_000L) }
        assertThat(counter.count).isEqualTo(JumpCounter.TARGET)
        assertThat(counter.isDone).isTrue()
    }
}

class AlarmRulesTest {
    private val alarm = Alarm(UUID.randomUUID(), AlarmLocation.PHONE, 7, 0)

    @Test
    fun snoozeIsNineMinutes() {
        assertThat(AlarmScheduler.snoozeTime(1_000L)).isEqualTo(1_000L + 9 * 60_000)
    }

    @Test
    fun scheduledAndSnoozeTriggersNeverCollide() {
        repeat(200) {
            val id = UUID.randomUUID()
            assertThat(AlarmScheduler.requestCode(id, 0)).isNotEqualTo(AlarmScheduler.requestCode(id, 1))
        }
    }

    @Test
    fun qrIsOnlyOfferedWithASavedCodeOrAsTheAlarmsOwnChallenge() {
        assertThat(switchableChallenges(alarm, DismissChallenge.MATH_PUZZLE)).containsExactly(DismissChallenge.JUMPING_JACKS)
        assertThat(switchableChallenges(alarm.copy(dismissQrCode = "x"), DismissChallenge.MATH_PUZZLE))
            .containsExactly(DismissChallenge.JUMPING_JACKS, DismissChallenge.QR_CODE_SCAN)
        assertThat(switchableChallenges(alarm.copy(dismissChallenge = DismissChallenge.QR_CODE_SCAN), DismissChallenge.QR_CODE_SCAN))
            .containsExactly(DismissChallenge.MATH_PUZZLE, DismissChallenge.JUMPING_JACKS)
    }
}
