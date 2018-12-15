package org.alephium.flow.constant

import java.time.Duration

object Consensus extends WithConfig {

  val numZerosAtLeastInHash: Int = config.getInt("numZerosAtLeastInHash")
  val maxMiningTarget: BigInt    = (BigInt(1) << (256 - numZerosAtLeastInHash)) - 1

  val blockTargetTime: Duration = config.getDuration("blockTargetTime")
  val blockSpanNum: Int         = config.getInt("blockSpanNum")
}
