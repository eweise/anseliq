package com.eweise.perfectpair.domain.model

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
  def isReconSpec: Boolean = this.isInstanceOf[ReconciliationAccountSpec]

case class ReconciliationAccountSpec(
    name: String,
    matcher: Matcher = EqualityMatcher(Array("id"), Array("id")),
    agingLimit: String = ""
) extends AccountSpec

case class RealAccountSpec(
    name: String
) extends AccountSpec



sealed trait Matcher derives ReadWriter
case class EqualityMatcher(
    inKeys: Array[String],
    outKeys: Array[String]
) extends Matcher

case class SQLMatcher(
    sql: String
) extends Matcher
