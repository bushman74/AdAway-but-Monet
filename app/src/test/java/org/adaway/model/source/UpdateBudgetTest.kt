package org.adaway.model.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class UpdateBudgetTest {
    @Test
    fun `a fresh budget is not exhausted`() {
        val budget = UpdateBudget(Duration.ofMinutes(7), 4) { 0L }

        assertFalse(budget.timedOut())
        assertFalse(budget.failing())
        assertFalse(budget.exhausted())
    }

    @Test
    fun `the budget runs out once its time is spent`() {
        var now = 1_000L
        val budget = UpdateBudget(Duration.ofSeconds(30), 4) { now }

        now = 1_000L + 29_999L
        assertFalse(budget.timedOut())

        now = 1_000L + 30_000L
        assertTrue(budget.timedOut())
        assertTrue(budget.exhausted())
    }

    @Test
    fun `failing sources in a row exhaust the budget`() {
        val budget = UpdateBudget(Duration.ofMinutes(7), 3) { 0L }

        budget.recordFailure()
        budget.recordFailure()
        assertFalse(budget.failing())

        budget.recordFailure()
        assertTrue(budget.failing())
        assertTrue(budget.exhausted())
    }

    @Test
    fun `a source that works clears the failures before it`() {
        val budget = UpdateBudget(Duration.ofMinutes(7), 3) { 0L }

        budget.recordFailure()
        budget.recordFailure()
        budget.recordSuccess()
        budget.recordFailure()
        budget.recordFailure()

        assertFalse(budget.failing())
    }

    @Test
    fun `spending the time exhausts the budget even while sources are working`() {
        var now = 0L
        val budget = UpdateBudget(Duration.ofSeconds(10), 3) { now }

        budget.recordSuccess()
        now = 10_000L

        assertTrue(budget.exhausted())
    }
}
