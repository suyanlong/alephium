package org.alephium.storage

import org.alephium.protocol.model.Block

import scala.collection.mutable.ArrayBuffer

class ConfirmedChain() {
  private val blocks: ArrayBuffer[Block] = ArrayBuffer.empty

  def add(block: Block): Unit = {
    blocks += block
  }
}

object ConfirmedChain {
  def apply(): ConfirmedChain = new ConfirmedChain()
}