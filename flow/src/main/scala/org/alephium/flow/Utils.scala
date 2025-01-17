// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.flow

import org.alephium.io.IOResult
import org.alephium.protocol.model.{ChainIndex, FlowData}
import org.alephium.serde.RandomBytes
import org.alephium.util.AVector

object Utils {
  def showDigest[T <: RandomBytes](elems: AVector[T]): String = {
    if (elems.isEmpty) "[]" else s"[ ${elems.head.shortHex} .. ${elems.last.shortHex} ]"
  }

  def showFlow[T <: RandomBytes](elems: AVector[AVector[T]]): String = {
    elems.map(showDigest).mkString(", ")
  }

  def showDataDigest[T <: FlowData](elems: AVector[T]): String = {
    if (elems.isEmpty) "[]" else s"[ ${elems.head.shortHex} .. ${elems.last.shortHex} ]"
  }

  def showDataFlow[T <: FlowData](elems: AVector[AVector[T]]): String = {
    elems.map(showDataDigest).mkString(", ")
  }

  def showChainIndexedDigest[T <: RandomBytes](elems: AVector[(ChainIndex, AVector[T])]): String = {
    elems.map(p => s"${p._1} -> ${showDigest(p._2)}").mkString(", ")
  }

  def unsafe[T](e: IOResult[T]): T =
    e match {
      case Right(t) => t
      case Left(e)  => throw e
    }
}
