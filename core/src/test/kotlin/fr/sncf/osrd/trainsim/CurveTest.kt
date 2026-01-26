package fr.sncf.osrd.trainsim

import kotlin.test.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull

class CurveTest {
    private val dummyCurve = Curve((0L..10L).map { Vec2(it, 10 - it) })

    @Test
    fun testGetPointAtNegativePosition() {
        assertNull(dummyCurve.getPointAt(-1))
    }

    @Test
    fun testGetPointAtZero() {
        val point = dummyCurve.getPointAt(0)
        assertNotNull(point)
        assertEquals(0, point.x)
        assertEquals(10, point.y)
    }

    @Test
    fun testGetPointAtEnd() {
        val point = dummyCurve.getPointAt(10)
        assertNotNull(point)
        assertEquals(10, point.x)
        assertEquals(0, point.y)
    }

    @Test
    fun testGetPointAtMiddle() {
        val point = dummyCurve.getPointAt(5)
        assertNotNull(point)
        assertEquals(5, point.x)
        assertEquals(5, point.y)
    }

    @Test
    fun testGetPointAtOutOfBoundsPosition() {
        // Almost in bound
        assertNull(dummyCurve.getPointAt(11))
        assertNull(dummyCurve.getPointAt(42))
    }

    @Test
    fun testLastGetLast() {
        val point = dummyCurve.last()
        assertNotNull(point)
        assertEquals(10, point.x)
        assertEquals(0, point.y)
    }

    @Test
    fun testLastGetFirst() {
        val point = dummyCurve.last(10)
        assertNotNull(point)
        assertEquals(0, point.x)
        assertEquals(10, point.y)
    }

    @Test
    fun testLastOutOfBounds() {
        // One after last
        assertNull(dummyCurve.last(-1))
        // One before beginning
        assertNull(dummyCurve.last(11))
    }

    @Test
    fun testLastGetMiddle() {
        val point = dummyCurve.last(4)
        assertNotNull(point)
        assertEquals(6, point.x)
        assertEquals(4, point.y)
    }

    @Test
    fun testIsBelowOverCurve() {
        val point = Vec2(4, 11)
        assertTrue(dummyCurve.isBelow(point))
    }

    @Test
    fun testIsBelowBelowCurve() {
        val point = Vec2(4, 2)
        assertFalse(dummyCurve.isBelow(point))
    }

    @Test
    fun testIsBelowOverAtEdge() {
        val point = Vec2(0, 11)
        assertTrue(dummyCurve.isBelow(point))
    }

    @Test
    fun testIsBelowUnderAtEdge() {
        val point = Vec2(0, 9)
        assertFalse(dummyCurve.isBelow(point))
    }

    @Test
    fun testIsBelowOnCurve() {
        val point = Vec2(5, 5)
        assertFalse(dummyCurve.isBelow(point))
    }
}
