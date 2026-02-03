package com.eweise.perfectpair.domain.model

import com.eweise.perfectpair.common.model.{EqualityMatcher, Matcher}
import upickle.default.*

enum Direction:
  case IN, OUT

case class MovementSpec(
  name:           String,
  inAccountName:  String,
  outAccountName: String,
  attributes:     Array[String] = Array.empty
) derives ReadWriter

case class FundFlowSpec(
  realAccountSpecs:  Seq[RealAccountSpec]           = Seq(),
  reconAccountSpecs: Seq[ReconciliationAccountSpec] = Seq(),
  movementSpecs:     Seq[MovementSpec]              = Seq()
) derives ReadWriter:

  def allAccounts: Seq[AccountSpec] =
    realAccountSpecs ++ reconAccountSpecs

private sealed trait AccountSpec derives ReadWriter:
  def name:        String
  def properties:  Array[String]
  def isReconSpec: Boolean = this.isInstanceOf[ReconciliationAccountSpec]

case class ReconciliationAccountSpec(
  name:       String,
  properties: Array[String] = Array(),
  matcher:    Matcher       = EqualityMatcher(Array("id"), Array("id")),
  agingLimit: String        = ""
) extends AccountSpec

case class RealAccountSpec(
  name:       String,
  properties: Array[String] = Array()
) extends AccountSpec
