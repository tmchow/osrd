package fr.sncf.osrd.stdcm.graph

import fr.sncf.osrd.sim_infra.api.Block
import fr.sncf.osrd.sim_infra.api.BlockId
import fr.sncf.osrd.utils.units.Offset
import java.math.RoundingMode

/**
 * This class is only used for caching simulations results, other parameters (that shouldn't be
 * considered to evaluate if previous results can be used) can be added on the side
 */
data class BlockSimulationParameters(
    val block: BlockId,
    val initialSpeed: Double,
    val start: Offset<Block>,
    val stop: Offset<Block>?,
) {
    fun round(): BlockSimulationParameters {
        return BlockSimulationParameters(
            block,
            initialSpeed.toBigDecimal().setScale(1, RoundingMode.UP).toDouble(),
            start,
            stop,
        )
    }
}
