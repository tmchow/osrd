package fr.sncf.osrd.path.interfaces

import fr.sncf.osrd.sim_infra.api.Block
import fr.sncf.osrd.sim_infra.api.Route
import fr.sncf.osrd.sim_infra.api.TrackChunk
import fr.sncf.osrd.utils.indexing.DirStaticIdx
import fr.sncf.osrd.utils.indexing.StaticIdx
import fr.sncf.osrd.utils.units.Offset
import fr.sncf.osrd.utils.units.Offset.Companion.max
import fr.sncf.osrd.utils.units.Offset.Companion.min
import fr.sncf.osrd.utils.units.meters

data class GenericLinearRange<ValueType, OffsetType>(
    val value: ValueType,
    val objectBegin: Offset<OffsetType>,
    val objectEnd: Offset<OffsetType>,
    val pathBegin: Offset<TrainPath>,
    val pathEnd: Offset<TrainPath>,
) {
    val length = objectEnd - objectBegin

    init {
        require(length >= 0.meters)
        require(pathEnd - pathBegin == length)
    }

    fun isSingleton() = length == 0.meters

    fun offsetFromTrainPath(pathOffset: Offset<TrainPath>): Offset<OffsetType> {
        val rangeOffset = pathOffset.distance - pathBegin.distance
        return objectBegin + rangeOffset
    }

    fun offsetToTrainPath(objectOffset: Offset<OffsetType>): Offset<TrainPath> {
        val rangeOffset = objectOffset.distance - objectBegin.distance
        return pathBegin + rangeOffset
    }

    fun withTruncatedPathRange(
        from: Offset<TrainPath>,
        to: Offset<TrainPath>,
    ): GenericLinearRange<ValueType, OffsetType>? {
        val newPathBegin = max(from, pathBegin)
        val newPathEnd = min(to, pathEnd)
        if (newPathBegin > newPathEnd) return null
        val removedAtStart = newPathBegin - pathBegin
        val removedAtEnd = pathEnd - newPathEnd
        assert(removedAtStart >= 0.meters)
        assert(removedAtEnd >= 0.meters)
        return GenericLinearRange(
            value,
            objectBegin + removedAtStart,
            objectEnd - removedAtEnd,
            newPathBegin,
            newPathEnd,
        )
    }

    fun <SubObjectType, SubObjectOffset> mapSubObject(
        subObjectList: List<SubObjectType>,
        getSubObjectLength: (SubObjectType) -> Offset<SubObjectOffset>,
    ): List<GenericLinearRange<SubObjectType, SubObjectOffset>> {
        var prevObjectEndPathOffset: Offset<TrainPath> = pathBegin - objectBegin.distance
        val res = mutableListOf<GenericLinearRange<SubObjectType, SubObjectOffset>>()
        for (subObject in subObjectList) {
            val subObjectLength = getSubObjectLength(subObject)
            val subObjectRange =
                GenericLinearRange(
                    subObject,
                    Offset.zero(),
                    subObjectLength,
                    prevObjectEndPathOffset,
                    prevObjectEndPathOffset + subObjectLength.distance,
                )
            val truncated = subObjectRange.withTruncatedPathRange(pathBegin, pathEnd)
            if (truncated != null) res.add(truncated)
            prevObjectEndPathOffset += subObjectLength.distance
        }
        return res
    }
}

typealias LinearObjectRange<T> = GenericLinearRange<StaticIdx<T>, T>

typealias LinearDirObjectRange<T> = GenericLinearRange<DirStaticIdx<T>, T>

typealias RouteRange = LinearObjectRange<Route>

typealias BlockRange = LinearObjectRange<Block>

typealias DirChunkRange = LinearDirObjectRange<TrackChunk>

fun <ValueType, OffsetType> mergeLinearRanges(
    vararg rangeLists: List<GenericLinearRange<ValueType, OffsetType>>
): List<GenericLinearRange<ValueType, OffsetType>> {
    val res = mutableListOf<GenericLinearRange<ValueType, OffsetType>>()
    var last: GenericLinearRange<ValueType, OffsetType>? = null
    for (rangeList in rangeLists) {
        for (entry in rangeList) {
            if (last?.value == entry.value) {
                assert(last.pathBegin <= entry.pathBegin)
                assert(last.objectBegin <= entry.objectBegin)
                last = last.copy(pathEnd = entry.pathEnd, objectEnd = entry.objectEnd)
            } else {
                last?.let { res.add(it) }
                last = entry
            }
        }
    }
    last?.let { res.add(it) }
    return res
}

fun <ValueType, OffsetType, SubObjectType, SubObjectOffset> mapSubObjects(
    outerObjectRanges: List<GenericLinearRange<ValueType, OffsetType>>,
    listSubObject: (ValueType) -> List<SubObjectType>,
    subObjectLength: (SubObjectType) -> Offset<SubObjectOffset>,
): List<GenericLinearRange<SubObjectType, SubObjectOffset>> {
    val res = mutableListOf<GenericLinearRange<SubObjectType, SubObjectOffset>>()
    for (range in outerObjectRanges) {
        val subRanges = range.mapSubObject(listSubObject(range.value), subObjectLength)
        res.addAll(subRanges)
    }
    return mergeLinearRanges(res)
}
