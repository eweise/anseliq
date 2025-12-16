package com.eweise.perfectpair.domain.model

import com.eweise.perfectpair.common.model.{EqualityMatcher, Matcher}
import upickle.default.*

enum Direction:
  case IN, OUT

case class MovementSpec(
  name: String,
  inAccountName: String,
  outAccountName: String,
  attributes: Array[String] = Array.empty
) derives ReadWriter

case class FundFlowSpec(
  accountSpecs: Seq[AccountSpec],
  movementSpecs: Seq[MovementSpec]
) derives ReadWriter

sealed trait AccountSpec derives ReadWriter:
  val name: String
  val properties: Array[String] = Array.empty
  def isReconSpec: Boolean      = this.isInstanceOf[ReconciliationAccountSpec]

case class ReconciliationAccountSpec(
  name: String,
  matcher: Matcher = EqualityMatcher(Array("id"), Array("id")),
  agingLimit: String = ""
  
) extends AccountSpec

case class RealAccountSpec(
  name: String
) extends AccountSpec

