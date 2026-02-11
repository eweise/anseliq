package com.eweise.perfectpair.common.model

import com.eweise.perfectpair.common.model.model.ID
import upickle.ReadWriter
import upickle.default.ReadWriter.join

sealed trait Matcher derives ReadWriter
case class EqualityMatcher(
  rule: AttributeMatchRule
) extends Matcher
    derives ReadWriter

case class SQLMatcher(
  sql: String
) extends Matcher
    derives ReadWriter

sealed trait Rule:
  def sql: String

  def createSqlDelimiter(rules: List[Rule], andOr: String): String = {
    var sqlAccum = ""
    val iter     = rules.iterator
    var isFirst  = true
    while (iter.hasNext) do
      val rule = iter.next
      sqlAccum = sqlAccum + (if isFirst then
                               isFirst = false
                               s""" ( ${rule.sql} """
                             else s""") $andOr ( ${rule.sql} )""")
    sqlAccum
  }

case class AndRule(rules: List[Rule]) extends Rule:
  def sql: String = createSqlDelimiter(rules, "AND")

case class ORRule(rules: List[Rule]) extends Rule:
  def sql: String = createSqlDelimiter(rules, "OR")

case class AttributeMatchRule(
  inMovementSpecID:  ID,
  outMovementSpecID: ID,
  inKey:             String,
  outKey:            String
) extends Rule
    derives ReadWriter:
  def sql: String =
    s""" a.movement_spec_id = '$inMovementSpecID'
       | and b.movement_spec_id= '$outMovementSpecID'
       | and a.properties ['props'] ['$inKey'] = b.properties ['props'] ['$outKey']""".stripMargin
