package fr.sncf.osrd.tsim

import com.google.common.collect.Range
import com.google.common.collect.TreeRangeMap
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class RangeTest {
    @Test
    fun testPutLower() {
        val map = TreeRangeMap.create<Micrometers, MicrometersPerSecond>()
        map.put(Range.all(), 42)

        Assertions.assertEquals(42, map.get(0))

        map.putLower(Range.closed(-1, 1), 69)
        Assertions.assertEquals(42, map.get(0))

        map.putLower(Range.closed(-1, 1), 31)
        Assertions.assertEquals(31, map.get(0))
        Assertions.assertEquals(42, map.get(2))
    }

    @Test
    fun testPutLower2() {
        val map = TreeRangeMap.create<Micrometers, MicrometersPerSecond>()
        map.put(Range.atMost(0), 42)
        map.put(Range.atLeast(0), 69)

        map.putLower(Range.closed(-2, 2), 420)
        Assertions.assertEquals(42, map.get(-1))
        Assertions.assertEquals(69, map.get(1))

        map.putLower(Range.closed(-2, 2), 50)
        Assertions.assertEquals(42, map.get(-1))
        Assertions.assertEquals(50, map.get(1))

        map.putLower(Range.closed(-2, 2), 2)
        Assertions.assertEquals(2, map.get(-1))
        Assertions.assertEquals(2, map.get(1))
    }

    @Test
    fun testPutLower3() {
        val map = TreeRangeMap.create<Micrometers, MicrometersPerSecond>()
        map.put(Range.closed(2, 4), 42)
        map.putLower(Range.closed(0, 6), 69)
        map.putLower(Range.all(), 420)

        Assertions.assertEquals(42, map.get(3))
        Assertions.assertEquals(69, map.get(1))
        Assertions.assertEquals(69, map.get(5))
        Assertions.assertEquals(420, map.get(-1))
        Assertions.assertEquals(420, map.get(7))
    }

    @Test
    fun testWithStockLength() {
        val map = TreeRangeMap.create<Micrometers, MicrometersPerSecond>()
        map.put(Range.closed(0, 689), 27_778)
        map.put(Range.closed(1187, 1193), 22_222)
        map.put(Range.closed(897, 1187), 27_778)
        map.put(Range.closed(689, 897), 19_444)

        val res = map.withStockLength(400)

        Assertions.assertEquals(27_778, res.get(0))
        Assertions.assertEquals(27_778, res.get(688))

        Assertions.assertEquals(19_444, res.get(690))
        Assertions.assertEquals(19_444, res.get(896))

        Assertions.assertEquals(19_444, res.get(898))
        Assertions.assertEquals(19_444, res.get(1186))

        Assertions.assertEquals(19_444, res.get(1188))
        Assertions.assertEquals(19_444, res.get(1192))
        Assertions.assertEquals(19_444, res.get(1296))

        Assertions.assertEquals(null, res.get(1298))
    }

    @Test
    fun testIntersectsAt() {
        val constraint = Curve(
            xs = longArrayOf(42_000_000),
            ys = longArrayOf(69_000_000),
        )
        val p = constraint.intersectsAt(
            x1 = 0,
            y1 = 64_000_000,
            x2 = 1_000_000,
            y2 = 70_000_000,
        )

        Assertions.assertEquals(Vec2(833_333, 69_000_000), p)
    }
}
