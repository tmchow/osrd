package fr.sncf.osrd.tsim

import com.google.common.collect.Range
import com.google.common.collect.TreeRangeMap
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class RangeTest {
    @Test
    fun testPutLower() {
        val map = TreeRangeMap.create<Meters, MetersPerSecond>()
        map.put(Range.all(), 42.0)

        Assertions.assertEquals(42.0, map.get(0.0))

        map.putLower(Range.closed(-1.0, 1.0), 69.0)
        Assertions.assertEquals(42.0, map.get(0.0))

        map.putLower(Range.closed(-1.0, 1.0), 31.0)
        Assertions.assertEquals(31.0, map.get(0.0))
        Assertions.assertEquals(42.0, map.get(2.0))
    }

    @Test
    fun testPutLower2() {
        val map = TreeRangeMap.create<Meters, MetersPerSecond>()
        map.put(Range.atMost(0.0), 42.0)
        map.put(Range.atLeast(0.0), 69.0)

        map.putLower(Range.closed(-1.0, 1.0), 420.0)
        Assertions.assertEquals(42.0, map.get(-0.5))
        Assertions.assertEquals(69.0, map.get(0.5))

        map.putLower(Range.closed(-1.0, 1.0), 50.0)
        Assertions.assertEquals(42.0, map.get(-0.5))
        Assertions.assertEquals(50.0, map.get(0.5))

        map.putLower(Range.closed(-1.0, 1.0), 2.0)
        Assertions.assertEquals(2.0, map.get(-0.5))
        Assertions.assertEquals(2.0, map.get(0.5))
    }

    @Test
    fun testPutLower3() {
        val map = TreeRangeMap.create<Meters, MetersPerSecond>()
        map.put(Range.closed(1.0, 2.0), 42.0)
        map.putLower(Range.closed(0.0, 3.0), 69.0)
        map.putLower(Range.all(), 420.0)

        Assertions.assertEquals(42.0, map.get(1.5))
        Assertions.assertEquals(69.0, map.get(0.5))
        Assertions.assertEquals(69.0, map.get(2.5))
        Assertions.assertEquals(420.0, map.get(-0.5))
        Assertions.assertEquals(420.0, map.get(3.5))
    }

    @Test
    fun testWithStockLength() {
        val map = TreeRangeMap.create<Meters, MetersPerSecond>()
        map.put(Range.closed(0.0, 689.0), 27.778)
        map.put(Range.closed(1187.0, 1193.0), 22.222)
        map.put(Range.closed(897.0, 1187.0), 27.778)
        map.put(Range.closed(689.0, 897.0), 19.444)

        val res = map.withStockLength(400.0)
        assert(res == null)
    }
}
