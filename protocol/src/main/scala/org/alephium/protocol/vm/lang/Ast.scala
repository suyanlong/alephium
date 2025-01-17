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

package org.alephium.protocol.vm.lang

import scala.collection.mutable

import org.alephium.protocol.vm.{Contract => VmContract, _}
import org.alephium.protocol.vm.lang.LogicalOperator.Not
import org.alephium.util.{AVector, I256, U256}

// scalastyle:off number.of.methods number.of.types file.size.limit
object Ast {
  final case class Ident(name: String)
  final case class TypeId(name: String)
  final case class FuncId(name: String, isBuiltIn: Boolean)
  final case class Argument(ident: Ident, tpe: Type, isMutable: Boolean, isUnused: Boolean) {
    def signature: String = {
      val prefix = if (isMutable) "mut " else ""
      s"${prefix}${ident.name}:${tpe.signature}"
    }
  }

  final case class EventField(ident: Ident, tpe: Type) {
    def signature: String = s"${ident.name}:${tpe.signature}"
  }

  final case class AnnotationField(ident: Ident, value: Val)
  final case class Annotation(id: Ident, fields: Seq[AnnotationField])

  object FuncId {
    def empty: FuncId = FuncId("", isBuiltIn = false)
  }

  final case class ApproveAsset[Ctx <: StatelessContext](
      address: Expr[Ctx],
      attoAlphAmountOpt: Option[Expr[Ctx]],
      tokenAmounts: Seq[(Expr[Ctx], Expr[Ctx])]
  ) {
    lazy val approveCount = (if (attoAlphAmountOpt.isEmpty) 0 else 1) + tokenAmounts.length

    def check(state: Compiler.State[Ctx]): Unit = {
      if (address.getType(state) != Seq(Type.Address)) {
        throw Compiler.Error(s"Invalid address type: ${address}")
      }
      if (attoAlphAmountOpt.exists(_.getType(state) != Seq(Type.U256))) {
        throw Compiler.Error(s"Invalid amount type: ${attoAlphAmountOpt}")
      }
      if (
        tokenAmounts
          .exists(p =>
            (p._1.getType(state), p._2.getType(state)) != (Seq(Type.ByteVec), Seq(Type.U256))
          )
      ) {
        throw Compiler.Error(s"Invalid token amount type: ${tokenAmounts}")
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      assume(approveCount >= 1)
      val approveAlph: Seq[Instr[Ctx]] = attoAlphAmountOpt match {
        case Some(amount) => amount.genCode(state) :+ ApproveAlph.asInstanceOf[Instr[Ctx]]
        case None         => Seq.empty
      }
      val approveTokens: Seq[Instr[Ctx]] = tokenAmounts.flatMap { case (tokenId, amount) =>
        tokenId.genCode(state) ++ amount.genCode(state) :+ ApproveToken.asInstanceOf[Instr[Ctx]]
      }
      address.genCode(state) ++ Seq.fill(approveCount - 1)(Dup) ++ approveAlph ++ approveTokens
    }
  }

  trait ApproveAssets[Ctx <: StatelessContext] {
    def approveAssets: Seq[ApproveAsset[Ctx]]

    def checkApproveAssets(state: Compiler.State[Ctx]): Unit = {
      approveAssets.foreach(_.check(state))
    }

    def genApproveCode(
        state: Compiler.State[Ctx],
        func: Compiler.FuncInfo[Ctx]
    ): Seq[Instr[Ctx]] = {
      (approveAssets.nonEmpty, func.usePreapprovedAssets) match {
        case (true, false) =>
          throw Compiler.Error(s"Function `${func.name}` does not use preapproved assets")
        case (false, true) =>
          throw Compiler.Error(
            s"Function `${func.name}` needs preapproved assets, please use braces syntax"
          )
        case _ => ()
      }
      approveAssets.flatMap(_.genCode(state))
    }
  }
  object ContractAssets {
    val contractAssetsInstrs: Set[Instr[_]] =
      Set(
        TransferAlphFromSelf,
        TransferTokenFromSelf,
        TransferAlphToSelf,
        TransferTokenToSelf,
        DestroySelf,
        SelfAddress
      )

    def checkCodeUsingContractAssets[Ctx <: StatelessContext](
        instrs: Seq[Instr[Ctx]],
        useAssetsInContract: Boolean,
        funcName: String
    ): Unit = {
      if (useAssetsInContract && !instrs.exists(contractAssetsInstrs.contains(_))) {
        throw Compiler.Error(
          s"Function `$funcName` does not use contract assets, but its annotation of contract assets is turn on"
        )
      }
    }
  }

  trait Typed[Ctx <: StatelessContext, T] {
    var tpe: Option[T] = None
    protected def _getType(state: Compiler.State[Ctx]): T
    def getType(state: Compiler.State[Ctx]): T =
      tpe match {
        case Some(ts) => ts
        case None =>
          val t = _getType(state)
          tpe = Some(t)
          t
      }
  }

  sealed trait Expr[Ctx <: StatelessContext] extends Typed[Ctx, Seq[Type]] {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]]
  }

  final case class Const[Ctx <: StatelessContext](v: Val) extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = Seq(Type.fromVal(v.tpe))

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      Seq(v.toConstInstr)
    }
  }
  final case class CreateArrayExpr[Ctx <: StatelessContext](elements: Seq[Expr[Ctx]])
      extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type.FixedSizeArray] = {
      assume(elements.nonEmpty)
      val baseType = elements(0).getType(state)
      if (baseType.length != 1) {
        throw Compiler.Error("Expect single type for array element")
      }
      if (elements.drop(0).exists(_.getType(state) != baseType)) {
        throw Compiler.Error(s"Array elements should have same type")
      }
      Seq(Type.FixedSizeArray(baseType(0), elements.size))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      elements.flatMap(_.genCode(state))
    }
  }
  def getConstantArrayIndex[Ctx <: StatelessContext](index: Expr[Ctx]): Int = {
    index match {
      case Ast.Const(Val.U256(v)) =>
        v.toInt.getOrElse(throw Compiler.Error(s"Invalid array index $v"))
      case _ => throw Compiler.Error(s"Invalid array index $index")
    }
  }
  final case class ArrayElement[Ctx <: StatelessContext](
      array: Expr[Ctx],
      indexes: Seq[Ast.Expr[Ctx]]
  ) extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      Seq(state.getArrayElementType(array, indexes))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val (arrayRef, codes) = state.getOrCreateArrayRef(array)
      getType(state) match {
        case Seq(_: Type.FixedSizeArray) =>
          codes ++ arrayRef.subArray(state, indexes).genLoadCode(state)
        case _ =>
          codes ++ arrayRef.genLoadCode(state, indexes)
      }
    }
  }
  final case class Variable[Ctx <: StatelessContext](id: Ident) extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = Seq(state.getType(id))

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      state.genLoadCode(id)
    }
  }
  final case class EnumFieldSelector[Ctx <: StatelessContext](enumId: TypeId, field: Ident)
      extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] =
      Seq(state.getVariable(EnumDef.fieldIdent(enumId, field)).tpe)

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val ident = EnumDef.fieldIdent(enumId, field)
      state.genLoadCode(ident)
    }
  }
  final case class UnaryOp[Ctx <: StatelessContext](op: Operator, expr: Expr[Ctx])
      extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      op.getReturnType(expr.getType(state))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      expr.genCode(state) ++ op.genCode(expr.getType(state))
    }
  }
  final case class Binop[Ctx <: StatelessContext](op: Operator, left: Expr[Ctx], right: Expr[Ctx])
      extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      op.getReturnType(left.getType(state) ++ right.getType(state))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      left.genCode(state) ++ right.genCode(state) ++ op.genCode(
        left.getType(state) ++ right.getType(state)
      )
    }
  }
  final case class ContractConv[Ctx <: StatelessContext](contractType: TypeId, address: Expr[Ctx])
      extends Expr[Ctx] {
    override protected def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      state.checkContractType(contractType)

      if (address.getType(state) != Seq(Type.ByteVec)) {
        throw Compiler.Error(s"Invalid expr $address for contract address")
      }

      if (!state.getContractInfo(contractType).kind.instantiable) {
        throw Compiler.Error(s"${contractType.name} is not instantiable")
      }

      Seq(Type.Contract.stack(contractType))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      address.genCode(state)
  }
  final case class CallExpr[Ctx <: StatelessContext](
      id: FuncId,
      approveAssets: Seq[ApproveAsset[Ctx]],
      args: Seq[Expr[Ctx]]
  ) extends Expr[Ctx]
      with ApproveAssets[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      checkApproveAssets(state)
      val funcInfo = state.getFunc(id)
      state.addInternalCall(id)
      funcInfo.getReturnType(args.flatMap(_.getType(state)))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val func = state.getFunc(id)
      genApproveCode(state, func) ++
        args.flatMap(_.genCode(state)) ++
        (if (func.isVariadic) Seq(U256Const(Val.U256.unsafe(args.length))) else Seq.empty) ++
        func.genCode(args.flatMap(_.getType(state)))
    }
  }

  trait ContractCallBase extends ApproveAssets[StatefulContext] {
    def obj: Expr[StatefulContext]
    def callId: FuncId
    def args: Seq[Expr[StatefulContext]]

    def _getTypeBase(state: Compiler.State[StatefulContext]): Seq[Type] = {
      val objType = obj.getType(state)
      if (objType.length != 1) {
        throw Compiler.Error(s"Expect single type from $obj")
      } else {
        objType(0) match {
          case contract: Type.Contract =>
            val funcInfo = state.getFunc(contract.id, callId)
            state.addExternalCall(contract.id, callId)
            funcInfo.getReturnType(args.flatMap(_.getType(state)))
          case _ =>
            throw Compiler.Error(s"Expect contract for $callId of $obj")
        }
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def genContractCall(
        state: Compiler.State[StatefulContext],
        popReturnValues: Boolean
    ): Seq[Instr[StatefulContext]] = {
      val contract  = obj.getType(state)(0).asInstanceOf[Type.Contract]
      val func      = state.getFunc(contract.id, callId)
      val argLength = Type.flattenTypeLength(func.argsType)
      val retLength = func.getReturnLength(args.flatMap(_.getType(state)))
      genApproveCode(state, func) ++
        args.flatMap(_.genCode(state)) ++
        Seq(
          ConstInstr.u256(Val.U256(U256.unsafe(argLength))),
          ConstInstr.u256(Val.U256(U256.unsafe(retLength)))
        ) ++
        obj.genCode(state) ++
        func.genExternalCallCode(contract.id) ++
        (if (popReturnValues) Seq.fill[Instr[StatefulContext]](retLength)(Pop) else Seq.empty)
    }
  }
  final case class ContractCallExpr(
      obj: Expr[StatefulContext],
      callId: FuncId,
      approveAssets: Seq[ApproveAsset[StatefulContext]],
      args: Seq[Expr[StatefulContext]]
  ) extends Expr[StatefulContext]
      with ContractCallBase {
    override def _getType(state: Compiler.State[StatefulContext]): Seq[Type] = {
      checkApproveAssets(state)
      _getTypeBase(state)
    }

    override def genCode(state: Compiler.State[StatefulContext]): Seq[Instr[StatefulContext]] = {
      genContractCall(state, false)
    }
  }
  final case class ParenExpr[Ctx <: StatelessContext](expr: Expr[Ctx]) extends Expr[Ctx] {
    override def _getType(state: Compiler.State[Ctx]): Seq[Type] =
      expr.getType(state: Compiler.State[Ctx])

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      expr.genCode(state)
  }

  trait IfBranch[Ctx <: StatelessContext] {
    def condition: Expr[Ctx]
    def checkCondition(state: Compiler.State[Ctx]): Unit = {
      val conditionType = condition.getType(state)
      if (conditionType != Seq(Type.Bool)) {
        throw Compiler.Error(s"Invalid type of condition expr: $conditionType")
      }
    }
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]]
  }
  trait ElseBranch[Ctx <: StatelessContext] {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]]
  }
  trait IfElse[Ctx <: StatelessContext] {
    def ifBranches: Seq[IfBranch[Ctx]]
    def elseBranchOpt: Option[ElseBranch[Ctx]]

    @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val ifBranchesIRs = Array.ofDim[Seq[Instr[Ctx]]](ifBranches.length + 1)
      val elseOffsets   = Array.ofDim[Int](ifBranches.length + 1)
      val elseBodyIRs   = elseBranchOpt.map(_.genCode(state)).getOrElse(Seq.empty)
      ifBranchesIRs(ifBranches.length) = elseBodyIRs
      elseOffsets(ifBranches.length) = elseBodyIRs.length
      ifBranches.zipWithIndex.view.reverse.foreach { case (ifBranch, index) =>
        val initialOffset    = elseOffsets(index + 1)
        val notTheLastBranch = index < ifBranches.length - 1 || elseBranchOpt.nonEmpty

        val bodyIRsWithoutOffset = ifBranch.genCode(state)
        val bodyOffsetIR = if (notTheLastBranch) {
          Seq(Jump(initialOffset))
        } else {
          Seq.empty
        }
        val bodyIRs = bodyIRsWithoutOffset ++ bodyOffsetIR

        val conditionOffset =
          if (notTheLastBranch) bodyIRs.length else bodyIRs.length + initialOffset
        val conditionIRs = Statement.getCondIR(ifBranch.condition, state, conditionOffset)
        ifBranchesIRs(index) = conditionIRs ++ bodyIRs
        elseOffsets(index) = initialOffset + bodyIRs.length + conditionIRs.length
      }
      ifBranchesIRs.reduce(_ ++ _)
    }
  }

  final case class IfBranchExpr[Ctx <: StatelessContext](
      condition: Expr[Ctx],
      expr: Expr[Ctx]
  ) extends IfBranch[Ctx] {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = expr.genCode(state)
  }
  final case class ElseBranchExpr[Ctx <: StatelessContext](
      expr: Expr[Ctx]
  ) extends ElseBranch[Ctx] {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = expr.genCode(state)
  }
  final case class IfElseExpr[Ctx <: StatelessContext](
      ifBranches: Seq[IfBranchExpr[Ctx]],
      elseBranch: ElseBranchExpr[Ctx]
  ) extends IfElse[Ctx]
      with Expr[Ctx] {
    def elseBranchOpt: Option[ElseBranch[Ctx]] = Some(elseBranch)

    def _getType(state: Compiler.State[Ctx]): Seq[Type] = {
      val elseBranchType = elseBranch.expr.getType(state)
      ifBranches.foreach { ifBranch =>
        ifBranch.checkCondition(state)
        val ifBranchType = ifBranch.expr.getType(state)
        if (ifBranchType != elseBranchType) {
          throw Compiler.Error(
            s"There are different types of if-else expression branches, expect $elseBranchType, have $ifBranchType"
          )
        }
      }
      elseBranchType
    }
  }

  sealed trait Statement[Ctx <: StatelessContext] {
    def check(state: Compiler.State[Ctx]): Unit
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]]
  }
  object Statement {
    @inline def getCondIR[Ctx <: StatelessContext](
        condition: Expr[Ctx],
        state: Compiler.State[Ctx],
        offset: Int
    ): Seq[Instr[Ctx]] = {
      condition match {
        case UnaryOp(Not, expr) =>
          expr.genCode(state) :+ IfTrue(offset)
        case _ =>
          condition.genCode(state) :+ IfFalse(offset)
      }
    }
  }

  sealed trait VarDeclaration
  final case class NamedVar(mutable: Boolean, ident: Ident) extends VarDeclaration
  case object AnonymousVar                                  extends VarDeclaration

  final case class VarDef[Ctx <: StatelessContext](
      vars: Seq[VarDeclaration],
      value: Expr[Ctx]
  ) extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      val types = value.getType(state)
      if (types.length != vars.length) {
        throw Compiler.Error(
          s"Invalid variable def, expect ${types.length} vars, have ${vars.length} vars"
        )
      }
      vars.zip(types).foreach {
        case (NamedVar(isMutable, ident), tpe) =>
          state.addLocalVariable(ident, tpe, isMutable, isUnused = false, isGenerated = false)
        case _ =>
      }
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val storeCodes = vars.zip(value.getType(state)).flatMap {
        case (NamedVar(_, ident), _) => state.genStoreCode(ident)
        case (AnonymousVar, tpe: Type.FixedSizeArray) =>
          Seq(Seq.fill(tpe.flattenSize())(Pop))
        case (AnonymousVar, _) => Seq(Seq(Pop))
      }
      value.genCode(state) ++ storeCodes.reverse.flatten
    }
  }

  sealed trait UniqueDef {
    def name: String
  }

  object UniqueDef {
    def checkDuplicates(defs: Seq[UniqueDef], name: String): Unit = {
      if (defs.distinctBy(_.name).size != defs.size) {
        throw Compiler.Error(s"These $name are defined multiple times: ${duplicates(defs)}")
      }
    }

    def duplicates(defs: Seq[UniqueDef]): String = {
      defs
        .groupBy(_.name)
        .filter(_._2.size > 1)
        .keys
        .mkString(", ")
    }
  }

  final case class FuncDef[Ctx <: StatelessContext](
      annotations: Seq[Annotation],
      id: FuncId,
      isPublic: Boolean,
      usePreapprovedAssets: Boolean,
      useAssetsInContract: Boolean,
      usePermissionCheck: Boolean,
      args: Seq[Argument],
      rtypes: Seq[Type],
      bodyOpt: Option[Seq[Statement[Ctx]]]
  ) extends UniqueDef {
    def name: String              = id.name
    def isPrivate: Boolean        = !isPublic
    val body: Seq[Statement[Ctx]] = bodyOpt.getOrElse(Seq.empty)

    def signature: String = {
      val publicPrefix = if (isPublic) "pub " else ""
      val assetModifier = {
        (usePreapprovedAssets, useAssetsInContract) match {
          case (true, true) =>
            s"@using(preapprovedAssets=true,assetsInContract=true) "
          case (true, false) =>
            s"@using(preapprovedAssets=true) "
          case (false, true) =>
            s"@using(assetsInContract=true) "
          case (false, false) =>
            ""
        }
      }
      s"${assetModifier}${publicPrefix}${name}(${args.map(_.signature).mkString(",")})->(${rtypes.map(_.signature).mkString(",")})"
    }
    def getArgNames(): AVector[String]          = AVector.from(args.view.map(_.ident.name))
    def getArgTypeSignatures(): AVector[String] = AVector.from(args.view.map(_.tpe.signature))
    def getArgMutability(): AVector[Boolean]    = AVector.from(args.view.map(_.isMutable))
    def getReturnSignatures(): AVector[String]  = AVector.from(rtypes.view.map(_.signature))

    def hasDirectPermissionCheck(): Boolean = {
      !usePermissionCheck || // permission check manually disabled
      body.exists {
        case FuncCall(id, _, _) => id.isBuiltIn && id.name == "checkPermission"
        case _                  => false
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def checkRetTypes(stmt: Option[Statement[Ctx]]): Unit = {
      stmt match {
        case Some(_: ReturnStmt[Ctx]) => () // we checked the `rtypes` in `ReturnStmt`
        case Some(IfElseStatement(ifBranches, elseBranchOpt)) =>
          ifBranches.foreach(branch => checkRetTypes(branch.body.lastOption))
          checkRetTypes(elseBranchOpt.flatMap(_.body.lastOption))
        case Some(call: FuncCall[_]) if call.id == FuncId("panic", isBuiltIn = true) => ()
        case _ => throw new Compiler.Error(s"Expect return statement for function ${id.name}")
      }
    }

    def check(state: Compiler.State[Ctx]): Unit = {
      state.checkArguments(args)
      args.foreach(arg =>
        state.addLocalVariable(arg.ident, arg.tpe, arg.isMutable, arg.isUnused, isGenerated = false)
      )
      body.foreach(_.check(state))
      state.checkUnusedLocalVars(id)
      if (rtypes.nonEmpty) checkRetTypes(body.lastOption)
    }

    def toMethod(state: Compiler.State[Ctx]): Method[Ctx] = {
      state.setFuncScope(id)
      check(state)

      val instrs    = body.flatMap(_.genCode(state))
      val localVars = state.getLocalVars(id)
      ContractAssets.checkCodeUsingContractAssets(instrs, useAssetsInContract, id.name)
      Method[Ctx](
        isPublic,
        usePreapprovedAssets,
        useAssetsInContract,
        argsLength = Type.flattenTypeLength(args.map(_.tpe)),
        localsLength = localVars.length,
        returnLength = Type.flattenTypeLength(rtypes),
        AVector.from(instrs)
      )
    }
  }

  object FuncDef {
    def main(
        stmts: Seq[Ast.Statement[StatefulContext]],
        usePreapprovedAssets: Boolean,
        useAssetsInContract: Boolean
    ): FuncDef[StatefulContext] = {
      FuncDef[StatefulContext](
        Seq.empty,
        id = FuncId("main", false),
        isPublic = true,
        usePreapprovedAssets = usePreapprovedAssets,
        useAssetsInContract = useAssetsInContract,
        usePermissionCheck = true,
        args = Seq.empty,
        rtypes = Seq.empty,
        bodyOpt = Some(stmts)
      )
    }
  }

  sealed trait AssignmentTarget[Ctx <: StatelessContext] extends Typed[Ctx, Type] {
    def ident: Ident
    def isMutable(state: Compiler.State[Ctx]): Boolean = state.getVariable(ident).isMutable
    def genStore(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]]
  }
  final case class AssignmentSimpleTarget[Ctx <: StatelessContext](ident: Ident)
      extends AssignmentTarget[Ctx] {
    def _getType(state: Compiler.State[Ctx]): Type                 = state.getVariable(ident).tpe
    def genStore(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = state.genStoreCode(ident)
  }
  final case class AssignmentArrayElementTarget[Ctx <: StatelessContext](
      ident: Ident,
      indexes: Seq[Ast.Expr[Ctx]]
  ) extends AssignmentTarget[Ctx] {
    def _getType(state: Compiler.State[Ctx]): Type =
      state.getArrayElementType(Seq(state.getVariable(ident).tpe), indexes)

    def genStore(state: Compiler.State[Ctx]): Seq[Seq[Instr[Ctx]]] = {
      val arrayRef = state.getArrayRef(ident)
      getType(state) match {
        case _: Type.FixedSizeArray => arrayRef.subArray(state, indexes).genStoreCode(state)
        case _                      => arrayRef.genStoreCode(state, indexes)
      }
    }
  }

  final case class ConstantVarDef(ident: Ident, value: Val) extends UniqueDef {
    def name: String = ident.name
  }

  final case class EnumField(ident: Ident, value: Val) extends UniqueDef {
    def name: String = ident.name
  }
  final case class EnumDef(id: TypeId, fields: Seq[EnumField]) extends UniqueDef {
    def name: String = id.name
  }
  object EnumDef {
    def fieldIdent(enumId: TypeId, field: Ident): Ident =
      Ident(s"${enumId.name}.${field.name}")
  }

  final case class EventDef(
      id: TypeId,
      fields: Seq[EventField]
  ) extends UniqueDef {
    def name: String = id.name

    def signature: String = s"event ${id.name}(${fields.map(_.signature).mkString(",")})"

    def getFieldNames(): AVector[String]          = AVector.from(fields.view.map(_.ident.name))
    def getFieldTypeSignatures(): AVector[String] = AVector.from(fields.view.map(_.tpe.signature))
  }

  final case class EmitEvent[Ctx <: StatefulContext](id: TypeId, args: Seq[Expr[Ctx]])
      extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      val eventInfo = state.getEvent(id)
      eventInfo.checkFieldTypes(args.flatMap(_.getType(state)))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val eventIndex = {
        val index = state.eventsInfo.map(_.typeId).indexOf(id)
        // `check` method ensures that this event is defined
        assume(index >= 0)

        Const[Ctx](Val.I256(I256.from(index))).genCode(state)
      }
      val argsType = args.flatMap(_.getType(state))
      if (argsType.exists(_.isArrayType)) {
        throw Compiler.Error(s"Array type not supported for event ${id.name}")
      }
      val logOpCode = Compiler.genLogs(args.length)
      eventIndex ++ args.flatMap(_.genCode(state)) :+ logOpCode
    }
  }

  final case class Assign[Ctx <: StatelessContext](
      targets: Seq[AssignmentTarget[Ctx]],
      rhs: Expr[Ctx]
  ) extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      val leftTypes  = targets.map(_.getType(state))
      val rightTypes = rhs.getType(state)
      if (leftTypes != rightTypes) {
        throw Compiler.Error(s"Assign $rightTypes to $leftTypes")
      }
      targets.foreach { target =>
        if (!target.isMutable(state)) {
          throw Compiler.Error(s"Assign to immutable variable: ${target.ident.name}")
        }
      }
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      rhs.genCode(state) ++ targets.flatMap(_.genStore(state)).reverse.flatten
    }
  }
  final case class FuncCall[Ctx <: StatelessContext](
      id: FuncId,
      approveAssets: Seq[ApproveAsset[Ctx]],
      args: Seq[Expr[Ctx]]
  ) extends Statement[Ctx]
      with ApproveAssets[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      checkApproveAssets(state)
      val funcInfo = state.getFunc(id)
      funcInfo.getReturnType(args.flatMap(_.getType(state)))
      state.addInternalCall(id)
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val func       = state.getFunc(id)
      val argsType   = args.flatMap(_.getType(state))
      val returnType = func.getReturnType(argsType)
      genApproveCode(state, func) ++
        args.flatMap(_.genCode(state)) ++
        (if (func.isVariadic) Seq(U256Const(Val.U256(U256.unsafe(args.length)))) else Seq.empty) ++
        func.genCode(argsType) ++
        Seq.fill(Type.flattenTypeLength(returnType))(Pop)
    }
  }
  final case class ContractCall(
      obj: Expr[StatefulContext],
      callId: FuncId,
      approveAssets: Seq[ApproveAsset[StatefulContext]],
      args: Seq[Expr[StatefulContext]]
  ) extends Statement[StatefulContext]
      with ContractCallBase {
    override def check(state: Compiler.State[StatefulContext]): Unit = {
      checkApproveAssets(state)
      _getTypeBase(state)
      ()
    }

    override def genCode(state: Compiler.State[StatefulContext]): Seq[Instr[StatefulContext]] = {
      genContractCall(state, true)
    }
  }

  final case class IfBranchStatement[Ctx <: StatelessContext](
      condition: Expr[Ctx],
      body: Seq[Statement[Ctx]]
  ) extends IfBranch[Ctx] {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = body.flatMap(_.genCode(state))
  }
  final case class ElseBranchStatement[Ctx <: StatelessContext](
      body: Seq[Statement[Ctx]]
  ) extends ElseBranch[Ctx] {
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = body.flatMap(_.genCode(state))
  }
  final case class IfElseStatement[Ctx <: StatelessContext](
      ifBranches: Seq[IfBranchStatement[Ctx]],
      elseBranchOpt: Option[ElseBranchStatement[Ctx]]
  ) extends IfElse[Ctx]
      with Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      ifBranches.foreach(_.checkCondition(state))
      ifBranches.foreach(_.body.foreach(_.check(state)))
      elseBranchOpt.foreach(_.body.foreach(_.check(state)))
    }
  }
  final case class While[Ctx <: StatelessContext](condition: Expr[Ctx], body: Seq[Statement[Ctx]])
      extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      if (condition.getType(state) != Seq(Type.Bool)) {
        throw Compiler.Error(s"Invalid type of condition expr $condition")
      }
      body.foreach(_.check(state))
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val bodyIR   = body.flatMap(_.genCode(state))
      val condIR   = Statement.getCondIR(condition, state, bodyIR.length + 1)
      val whileLen = condIR.length + bodyIR.length + 1
      if (whileLen > 0xff) {
        // TODO: support long branches
        throw Compiler.Error(s"Too many instrs for if-else branches")
      }
      condIR ++ bodyIR :+ Jump(-whileLen)
    }
  }
  final case class ForLoop[Ctx <: StatelessContext](
      initialize: Statement[Ctx],
      condition: Expr[Ctx],
      update: Statement[Ctx],
      body: Seq[Statement[Ctx]]
  ) extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      initialize.check(state)
      if (condition.getType(state) != Seq(Type.Bool)) {
        throw Compiler.Error(s"Invalid condition type: $condition")
      }
      body.foreach(_.check(state))
      update.check(state)
    }

    override def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] = {
      val initializeIR   = initialize.genCode(state)
      val bodyIR         = body.flatMap(_.genCode(state))
      val updateIR       = update.genCode(state)
      val fullBodyLength = bodyIR.length + updateIR.length + 1
      val condIR         = Statement.getCondIR(condition, state, fullBodyLength)
      val jumpLength     = condIR.length + fullBodyLength
      initializeIR ++ condIR ++ bodyIR ++ updateIR :+ Jump(-jumpLength)
    }
  }
  final case class ReturnStmt[Ctx <: StatelessContext](exprs: Seq[Expr[Ctx]])
      extends Statement[Ctx] {
    override def check(state: Compiler.State[Ctx]): Unit = {
      state.checkReturn(exprs.flatMap(_.getType(state)))
    }
    def genCode(state: Compiler.State[Ctx]): Seq[Instr[Ctx]] =
      exprs.flatMap(_.genCode(state)) :+ Return
  }

  trait ContractT[Ctx <: StatelessContext] {
    def ident: TypeId
    def templateVars: Seq[Argument]
    def fields: Seq[Argument]
    def funcs: Seq[FuncDef[Ctx]]

    def builtInContractFuncs(): Seq[Compiler.ContractFunc[Ctx]]

    lazy val funcTable: Map[FuncId, Compiler.ContractFunc[Ctx]] = {
      val builtInFuncs = builtInContractFuncs()
      var table = Compiler.SimpleFunc
        .from(funcs)
        .map(f => f.id -> f)
        .toMap[FuncId, Compiler.ContractFunc[Ctx]]
      builtInFuncs.foreach(func => table = table + (FuncId(func.name, isBuiltIn = true) -> func))
      if (table.size != (funcs.size + builtInFuncs.length)) {
        val duplicates = UniqueDef.duplicates(funcs)
        throw Compiler.Error(s"These functions are defined multiple times: $duplicates")
      }
      table
    }

    def check(state: Compiler.State[Ctx]): Unit = {
      state.checkArguments(fields)
      templateVars.zipWithIndex.foreach { case (temp, index) =>
        state.addTemplateVariable(temp.ident, temp.tpe, index)
      }
      fields.foreach(field =>
        state.addFieldVariable(
          field.ident,
          field.tpe,
          field.isMutable,
          field.isUnused,
          isGenerated = false
        )
      )
    }

    def getMethods(state: Compiler.State[Ctx]): AVector[Method[Ctx]] = {
      val methods = AVector.from(funcs.view.map(_.toMethod(state)))
      state.checkUnusedFields()
      methods
    }

    def genCode(state: Compiler.State[Ctx]): VmContract[Ctx]
  }

  final case class AssetScript(
      ident: TypeId,
      templateVars: Seq[Argument],
      funcs: Seq[FuncDef[StatelessContext]]
  ) extends ContractT[StatelessContext] {
    val fields: Seq[Argument] = Seq.empty

    def builtInContractFuncs(): Seq[Compiler.ContractFunc[StatelessContext]] = Seq.empty

    def genCode(state: Compiler.State[StatelessContext]): StatelessScript = {
      check(state)
      StatelessScript.from(getMethods(state)).getOrElse(throw Compiler.Error("Empty methods"))
    }
  }

  sealed trait ContractWithState extends ContractT[StatefulContext] {
    def ident: TypeId
    def name: String = ident.name
    def inheritances: Seq[Inheritance]

    def templateVars: Seq[Argument]
    def fields: Seq[Argument]
    def events: Seq[EventDef]
    def constantVars: Seq[ConstantVarDef]
    def enums: Seq[EnumDef]

    def builtInContractFuncs(): Seq[Compiler.ContractFunc[StatefulContext]] = Seq.empty

    def eventsInfo(): Seq[Compiler.EventInfo] = {
      UniqueDef.checkDuplicates(events, "events")
      events.map { event =>
        Compiler.EventInfo(event.id, event.fields.map(_.tpe))
      }
    }
  }

  final case class TxScript(
      ident: TypeId,
      templateVars: Seq[Argument],
      funcs: Seq[FuncDef[StatefulContext]]
  ) extends ContractWithState {
    val fields: Seq[Argument]                  = Seq.empty
    val events: Seq[EventDef]                  = Seq.empty
    val inheritances: Seq[ContractInheritance] = Seq.empty

    def error(tpe: String): Compiler.Error =
      new Compiler.Error(s"TxScript ${ident.name} should not contain any $tpe")
    def constantVars: Seq[ConstantVarDef] = throw error("constant variable")
    def enums: Seq[EnumDef]               = throw error("enum")
    def getTemplateVarsSignature(): String =
      s"TxScript ${name}(${templateVars.map(_.signature).mkString(",")})"
    def getTemplateVarsNames(): AVector[String] = AVector.from(templateVars.view.map(_.ident.name))
    def getTemplateVarsTypes(): AVector[String] =
      AVector.from(templateVars.view.map(_.tpe.signature))
    def getTemplateVarsMutability(): AVector[Boolean] =
      AVector.from(templateVars.view.map(_.isMutable))

    def genCode(state: Compiler.State[StatefulContext]): StatefulScript = {
      check(state)
      StatefulScript
        .from(getMethods(state))
        .getOrElse(
          throw Compiler.Error(
            "Expect the 1st function to be public and the other functions to be private for tx script"
          )
        )
    }
  }

  sealed trait Inheritance {
    def parentId: TypeId
  }
  final case class ContractInheritance(parentId: TypeId, idents: Seq[Ident]) extends Inheritance
  final case class InterfaceInheritance(parentId: TypeId)                    extends Inheritance
  final case class Contract(
      isAbstract: Boolean,
      ident: TypeId,
      templateVars: Seq[Argument],
      fields: Seq[Argument],
      funcs: Seq[FuncDef[StatefulContext]],
      events: Seq[EventDef],
      constantVars: Seq[ConstantVarDef],
      enums: Seq[EnumDef],
      inheritances: Seq[Inheritance]
  ) extends ContractWithState {
    def getFieldsSignature(): String =
      s"Contract ${name}(${fields.map(_.signature).mkString(",")})"
    def getFieldNames(): AVector[String]       = AVector.from(fields.view.map(_.ident.name))
    def getFieldTypes(): AVector[String]       = AVector.from(fields.view.map(_.tpe.signature))
    def getFieldMutability(): AVector[Boolean] = AVector.from(fields.view.map(_.isMutable))

    private def checkFuncs(): Unit = {
      if (funcs.length < 1) {
        throw Compiler.Error(s"No function definition in Contract ${ident.name}")
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
    def getFuncUnsafe(funcId: FuncId): FuncDef[StatefulContext] = funcs.find(_.id == funcId).get

    private def checkConstants(state: Compiler.State[StatefulContext]): Unit = {
      UniqueDef.checkDuplicates(constantVars, "constant variables")
      constantVars.foreach(v =>
        state.addConstantVariable(v.ident, Type.fromVal(v.value.tpe), Seq(v.value.toConstInstr))
      )
      UniqueDef.checkDuplicates(enums, "enums")
      enums.foreach(e =>
        e.fields.foreach(field =>
          state.addConstantVariable(
            EnumDef.fieldIdent(e.id, field.ident),
            Type.fromVal(field.value.tpe),
            Seq(field.value.toConstInstr)
          )
        )
      )
    }

    private def checkInheritances(state: Compiler.State[StatefulContext]): Unit = {
      inheritances.foreach { inheritance =>
        val id   = inheritance.parentId
        val kind = state.getContractInfo(id).kind
        if (!kind.inheritable) {
          throw Compiler.Error(s"$kind ${id.name} can not be inherited")
        }
      }
    }

    override def check(state: Compiler.State[StatefulContext]): Unit = {
      checkFuncs()
      checkConstants(state)
      checkInheritances(state)
      super.check(state)
    }

    def genCode(state: Compiler.State[StatefulContext]): StatefulContract = {
      val unimplementedFuncs = funcs.view.filter(_.bodyOpt.isEmpty).map(_.id.name)
      if (unimplementedFuncs.nonEmpty) {
        throw new Compiler.Error(
          s"These functions are not implemented in contract ${ident.name}: ${unimplementedFuncs.mkString(",")}"
        )
      } else {
        check(state)
        StatefulContract(
          Type.flattenTypeLength(fields.map(_.tpe)),
          getMethods(state)
        )
      }
    }

    // the state must have been updated in the check pass
    def buildPermissionCheckTable(
        state: Compiler.State[StatefulContext]
    ): mutable.Map[FuncId, Boolean] = {
      val internalCalls = state.internalCalls // caller -> callees
      val internalCallsReversed = // callee -> callers
        mutable.Map.empty[FuncId, mutable.ArrayBuffer[FuncDef[StatefulContext]]]
      internalCalls.foreach { case (caller, callees) =>
        val callerFunc = getFuncUnsafe(caller)
        callees.foreach { callee =>
          internalCallsReversed.get(callee) match {
            case None => internalCallsReversed.update(callee, mutable.ArrayBuffer(callerFunc))
            case Some(callers) => callers.addOne(callerFunc)
          }
        }
      }

      val permissionCheckedTable = mutable.Map.empty[FuncId, Boolean]
      funcs.foreach(func => permissionCheckedTable(func.id) = false)

      // TODO: optimize these two functions
      def updateCheckedRecursivelyForPrivateMethod(checkedPrivateCalleeId: FuncId): Unit = {
        internalCallsReversed.get(checkedPrivateCalleeId) match {
          case Some(callers) =>
            callers.foreach { caller =>
              updateCheckedRecursively(caller)
            }
          case None => ()
        }
      }
      def updateCheckedRecursively(func: FuncDef[StatefulContext]): Unit = {
        if (!permissionCheckedTable(func.id)) {
          permissionCheckedTable(func.id) = true
          if (func.isPrivate) { // indirect permission check should be in private methods
            updateCheckedRecursivelyForPrivateMethod(func.id)
          }
        }
      }

      funcs.foreach { func =>
        if (func.hasDirectPermissionCheck()) {
          updateCheckedRecursively(func)
        }
      }
      permissionCheckedTable
    }

    def validateInterfaceFuncsPermissionCheck(
        interfaceFuncsSize: Int,
        state: Compiler.State[StatefulContext]
    ): Unit = {
      val permissionTable = buildPermissionCheckTable(state)
      funcs.slice(0, interfaceFuncsSize).foreach { case func =>
        if (func.usePermissionCheck && !permissionTable(func.id)) {
          throw Compiler.Error(MultiContract.noPermissionCheckMsg(ident.name, func.id.name))
        }
      }
    }
  }

  final case class ContractInterface(
      ident: TypeId,
      funcs: Seq[FuncDef[StatefulContext]],
      events: Seq[EventDef],
      inheritances: Seq[InterfaceInheritance]
  ) extends ContractWithState {
    def error(tpe: String): Compiler.Error =
      new Compiler.Error(s"Interface ${ident.name} does not contain any $tpe")

    def templateVars: Seq[Argument]       = throw error("template variable")
    def fields: Seq[Argument]             = throw error("field")
    def getFieldsSignature(): String      = throw error("field")
    def getFieldTypes(): Seq[String]      = throw error("field")
    def constantVars: Seq[ConstantVarDef] = throw error("constant variable")
    def enums: Seq[EnumDef]               = throw error("enum")

    def genCode(state: Compiler.State[StatefulContext]): StatefulContract = {
      throw new Compiler.Error(s"Interface ${ident.name} does not generate code")
    }
  }

  final case class MultiContract(contracts: Seq[ContractWithState]) {
    def get(contractIndex: Int): ContractWithState = {
      if (contractIndex >= 0 && contractIndex < contracts.size) {
        contracts(contractIndex)
      } else {
        throw Compiler.Error(s"Invalid contract index $contractIndex")
      }
    }

    private def getContract(typeId: TypeId): ContractWithState = {
      contracts.find(_.ident == typeId) match {
        case None              => throw Compiler.Error(s"Contract $typeId does not exist")
        case Some(_: TxScript) => throw Compiler.Error(s"Expect contract $typeId, but got script")
        case Some(contract: ContractWithState) => contract
      }
    }

    private def getInterfaceOpt(typeId: TypeId): Option[ContractInterface] = {
      getContract(typeId) match {
        case interface: ContractInterface => Some(interface)
        case _                            => None
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def buildDependencies(
        contract: ContractWithState,
        parentsCache: mutable.Map[TypeId, Seq[ContractWithState]],
        visited: mutable.Set[TypeId]
    ): Unit = {
      if (!visited.add(contract.ident)) {
        throw Compiler.Error(s"Cyclic inheritance detected for contract ${contract.ident.name}")
      }

      val allParents = mutable.LinkedHashMap.empty[TypeId, ContractWithState]
      contract.inheritances.foreach { inheritance =>
        val parentId       = inheritance.parentId
        val parentContract = getContract(parentId)
        MultiContract.checkInheritanceFields(contract, inheritance, parentContract)

        allParents += parentId -> parentContract
        if (!parentsCache.contains(parentId)) {
          buildDependencies(parentContract, parentsCache, visited)
        }
        parentsCache(parentId).foreach { grandParent =>
          allParents += grandParent.ident -> grandParent
        }
      }
      parentsCache += contract.ident -> allParents.values.toSeq
    }

    private def buildDependencies(): mutable.Map[TypeId, Seq[ContractWithState]] = {
      val parentsCache = mutable.Map.empty[TypeId, Seq[ContractWithState]]
      val visited      = mutable.Set.empty[TypeId]
      contracts.foreach {
        case _: TxScript => ()
        case contract =>
          if (!parentsCache.contains(contract.ident)) {
            buildDependencies(contract, parentsCache, visited)
          }
      }
      parentsCache
    }

    @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
    def extendedContracts(): MultiContract = {
      val parentsCache = buildDependencies()
      val newContracts: Seq[ContractWithState] = contracts.map {
        case script: TxScript =>
          script
        case c: Contract =>
          val (funcs, events, constantVars, enums) = MultiContract.extractDefs(parentsCache, c)
          Contract(
            c.isAbstract,
            c.ident,
            c.templateVars,
            c.fields,
            funcs,
            events,
            constantVars,
            enums,
            c.inheritances
          )
        case i: ContractInterface =>
          val (funcs, events, _, _) = MultiContract.extractDefs(parentsCache, i)
          ContractInterface(i.ident, funcs, events, i.inheritances)
      }
      MultiContract(newContracts)
    }

    def genStatefulScript(contractIndex: Int): (StatefulScript, TxScript, AVector[String]) = {
      val state = Compiler.State.buildFor(this, contractIndex)
      get(contractIndex) match {
        case script: TxScript => (script.genCode(state), script, state.getWarnings)
        case _: Contract      => throw Compiler.Error(s"The code is for Contract, not for TxScript")
        case _: ContractInterface =>
          throw Compiler.Error(s"The code is for Interface, not for TxScript")
      }
    }

    private[vm] def checkExternalCallPermissions(
        states: AVector[Compiler.State[StatefulContext]],
        contractIndex: Int,
        contract: Contract
    ): Unit = {
      val contractState = states(contractIndex)
      val externalCalls =
        contractState.externalCalls.values.foldLeft(mutable.Set.empty[(TypeId, FuncId)])(_ ++ _)
      val externalCallPermissionTables = mutable.Map.empty[TypeId, mutable.Map[FuncId, Boolean]]
      externalCalls.foreach { case (calleeTypeId, _) =>
        if (!externalCallPermissionTables.contains(calleeTypeId)) {
          getContract(calleeTypeId) match {
            case calleeContract: Contract =>
              val calleeContractIndex = contracts.indexWhere(_.ident == calleeContract.ident)
              val calleeContractState = states(calleeContractIndex)
              val table = calleeContract.buildPermissionCheckTable(calleeContractState)
              externalCallPermissionTables.update(calleeTypeId, table)
            case calleeInterface: ContractInterface =>
              // Skip permission checks for interface function calls
              val table = mutable.Map.from(calleeInterface.funcs.map(_.id -> true))
              externalCallPermissionTables.update(calleeTypeId, table)
            case _ => ()
          }
        }
      }
      val allNoPermissionChecks: mutable.Set[(TypeId, FuncId)] = mutable.Set.empty
      contract.funcs.foreach { func =>
        // To check that external calls should have permission checks
        contractState.externalCalls.get(func.id) match {
          case Some(callees) if callees.nonEmpty =>
            callees.foreach { case funcRef @ (typeId, funcId) =>
              if (!externalCallPermissionTables(typeId)(funcId)) {
                allNoPermissionChecks.addOne(funcRef)
              }
            }
          case _ => ()
        }
      }
      allNoPermissionChecks.foreach { case (typeId, funcId) =>
        contractState.warnings.addOne(
          MultiContract.noPermissionCheckMsg(typeId.name, funcId.name)
        )
      }
    }

    def genStatefulContract(contractIndex: Int): (StatefulContract, Contract, AVector[String]) = {
      get(contractIndex) match {
        case contract: Contract =>
          val states       = AVector.tabulate(contracts.length)(Compiler.State.buildFor(this, _))
          val state        = states(contractIndex)
          val contractCode = contract.genCode(state)
          val interfaceFuncsSize = contract.inheritances.view.map { case inheritance =>
            getInterfaceOpt(inheritance.parentId).map(_.funcs.size).getOrElse(0)
          }.sum
          contract.validateInterfaceFuncsPermissionCheck(interfaceFuncsSize, state)
          states.foreachWithIndex { case (state, index) =>
            if (index != contractIndex) {
              contracts(index) match {
                case anotherContract: Contract if !anotherContract.isAbstract =>
                  anotherContract.genCode(state)
                case _ => ()
              }
            }
          }
          checkExternalCallPermissions(states, contractIndex, contract)
          (contractCode, contract, state.getWarnings)
        case _: TxScript => throw Compiler.Error(s"The code is for TxScript, not for Contract")
        case _: ContractInterface =>
          throw Compiler.Error(s"The code is for Interface, not for Contract")
      }
    }
  }

  object MultiContract {
    def checkInheritanceFields(
        contract: ContractWithState,
        inheritance: Inheritance,
        parentContract: ContractWithState
    ): Unit = {
      inheritance match {
        case i: ContractInheritance => _checkInheritanceFields(contract, i, parentContract)
        case _                      => ()
      }
    }
    private def _checkInheritanceFields(
        contract: ContractWithState,
        inheritance: ContractInheritance,
        parentContract: ContractWithState
    ): Unit = {
      val fields = inheritance.idents.map { ident =>
        contract.fields
          .find(_.ident == ident)
          .getOrElse(
            throw Compiler.Error(s"Contract field ${ident.name} does not exist")
          )
      }
      if (fields != parentContract.fields) {
        throw Compiler.Error(
          s"Invalid contract inheritance fields, expect ${parentContract.fields}, have $fields"
        )
      }
    }

    @SuppressWarnings(Array("org.wartremover.warts.IsInstanceOf"))
    def extractDefs(
        parentsCache: mutable.Map[TypeId, Seq[ContractWithState]],
        contract: ContractWithState
    ): (Seq[FuncDef[StatefulContext]], Seq[EventDef], Seq[ConstantVarDef], Seq[EnumDef]) = {
      val parents = parentsCache(contract.ident)
      val (allContracts, _allInterfaces) =
        (parents :+ contract).partition(_.isInstanceOf[Contract])
      val allInterfaces =
        sortInterfaces(parentsCache, _allInterfaces.map(_.asInstanceOf[ContractInterface]))

      val allFuncs                             = (allInterfaces ++ allContracts).flatMap(_.funcs)
      val (abstractFuncs, nonAbstractFuncs)    = allFuncs.partition(_.bodyOpt.isEmpty)
      val (unimplementedFuncs, allUniqueFuncs) = checkFuncs(abstractFuncs, nonAbstractFuncs)
      val constantVars                         = allContracts.flatMap(_.constantVars)
      val enums                                = allContracts.flatMap(_.enums)

      val contractEvents = allContracts.flatMap(_.events)
      val events         = allInterfaces.flatMap(_.events) ++ contractEvents

      val resultFuncs = contract match {
        case _: TxScript =>
          throw Compiler.Error("Extract definitions from TxScript is unexpected")
        case txContract: Contract =>
          if (!txContract.isAbstract && unimplementedFuncs.nonEmpty) {
            val methodNames = unimplementedFuncs.map(_.name).mkString(",")
            throw Compiler.Error(
              s"Contract ${txContract.name} has unimplemented methods: $methodNames"
            )
          }

          allUniqueFuncs
        case interface: ContractInterface =>
          if (nonAbstractFuncs.nonEmpty) {
            val methodNames = nonAbstractFuncs.map(_.name).mkString(",")
            throw Compiler.Error(
              s"Interface ${interface.name} has implemented methods: $methodNames"
            )
          }
          unimplementedFuncs
      }

      (resultFuncs, events, constantVars, enums)
    }

    private def sortInterfaces(
        parentsCache: mutable.Map[TypeId, Seq[ContractWithState]],
        allInterfaces: Seq[ContractInterface]
    ): Seq[ContractInterface] = {
      allInterfaces.sortBy(interface => parentsCache(interface.ident).length)
    }

    def checkFuncs(
        abstractFuncs: Seq[FuncDef[StatefulContext]],
        nonAbstractFuncs: Seq[FuncDef[StatefulContext]]
    ): (Seq[FuncDef[StatefulContext]], Seq[FuncDef[StatefulContext]]) = {
      val nonAbstractFuncSet = nonAbstractFuncs.view.map(f => f.id.name -> f).toMap
      val abstractFuncsSet   = abstractFuncs.view.map(f => f.id.name -> f).toMap
      if (nonAbstractFuncSet.size != nonAbstractFuncs.size) {
        val duplicates = UniqueDef.duplicates(nonAbstractFuncs)
        throw Compiler.Error(s"These functions are implemented multiple times: $duplicates")
      }

      if (abstractFuncsSet.size != abstractFuncs.size) {
        val duplicates = UniqueDef.duplicates(abstractFuncs)
        throw Compiler.Error(s"These abstract functions are defined multiple times: $duplicates")
      }

      val (implementedFuncs, unimplementedFuncs) =
        abstractFuncs.partition(func => nonAbstractFuncSet.contains(func.id.name))

      implementedFuncs.foreach { abstractFunc =>
        val funcName                = abstractFunc.id.name
        val implementedAbstractFunc = nonAbstractFuncSet(funcName)
        if (implementedAbstractFunc.copy(bodyOpt = None) != abstractFunc) {
          throw new Compiler.Error(s"Function ${funcName} is implemented with wrong signature")
        }
      }

      val inherited    = abstractFuncs.map { f => nonAbstractFuncSet.getOrElse(f.id.name, f) }
      val nonInherited = nonAbstractFuncs.filter(f => !abstractFuncsSet.contains(f.id.name))
      (unimplementedFuncs, inherited ++ nonInherited)
    }

    def noPermissionCheckMsg(typeId: String, funcId: String): String =
      s"No permission check for function: ${typeId}.${funcId}, please use checkPermission!(...) for the function or its private callees."
  }
}
// scalastyle:on number.of.methods number.of.types
