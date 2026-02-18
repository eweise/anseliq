package perfectpair.common.model

import perfectpair.common.model.model.ID
import upickle.ReadWriter
import upickle.default.ReadWriter.join

sealed trait Matcher derives ReadWriter
case class EqualityMatcher(
  rule: Rule
) extends Matcher
    derives ReadWriter

case class SQLMatcher(
  sql: String
) extends Matcher
    derives ReadWriter

sealed trait Rule derives ReadWriter:
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
  inKey:             Attribute,
  outKey:            Attribute
) extends Rule
    derives ReadWriter:

  def sql: String =
    val inAttributeFrag  = s" move_in.properties ['props'] ['${inKey.key}']"
    val outAttributeFrag = s" move_out.properties ['props'] ['${outKey.key}']"
    val inModifiedFrag   = inKey.modify(inAttributeFrag)
    val outModifiedFrag  = outKey.modify(outAttributeFrag)
    s""" move_in.movement_spec_id = '${inMovementSpecID.toString}'
       | and move_out.movement_spec_id = '${outMovementSpecID.toString}'
       | and $inModifiedFrag = $outModifiedFrag""".stripMargin

sealed abstract class Attribute(val key: String, val modifier: Modifier) derives ReadWriter:
  def modify(frag: String): String

case class StringAttribute(
  override val key:      String,
  override val modifier: Modifier = NoModifier
) extends Attribute(key = key, modifier = modifier):
  def modify(frag: String): String = modifier.modify(frag + "::text")

case class DateAttribute(
  override val key:      String,
  override val modifier: Modifier = NoModifier
) extends Attribute(key = key, modifier = modifier):
  def modify(frag: String): String = modifier.modify(frag + "::text::date")

sealed trait Modifier derives ReadWriter:
  def modify(frad: String): String

case class DateAdd(interval: Interval, value: Int) extends Modifier:
  def modify(frag: String): String = s"($frag + interval '$value $interval')::date"

case object NoModifier extends Modifier:
  def modify(frag: String): String = frag

enum Interval derives ReadWriter:
  case Year, Month, Day, Hour, Minute
