package perfectpair.common.model

import perfectpair.common.model.model.{ID, Properties}
import upickle.ReadWriter
import upickle.default.ReadWriter.join

import java.text.DateFormat
import java.util.Date

sealed trait Rule derives ReadWriter:
  def sql: String
//  def isMatch:Boolean

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

case class StringAttributeMatchRule(
  inMovementSpecID:  ID,
  outMovementSpecID: ID,
  inKey:             StringAttribute,
  outKey:            StringAttribute
) extends Rule
    derives ReadWriter:

  def sql: String =
    val inAttributeFrag  = s" move_in.properties ['props'] ['${inKey.key}']"
    val outAttributeFrag = s" move_out.properties ['props'] ['${outKey.key}']"
    val inModifiedFrag   = inKey.modifySQL(inAttributeFrag)
    val outModifiedFrag  = outKey.modifySQL(outAttributeFrag)
    s""" move_in.movement_spec_id = '${inMovementSpecID.toString}'
       | and move_out.movement_spec_id = '${outMovementSpecID.toString}'
       | and $inModifiedFrag = $outModifiedFrag""".stripMargin

case class DateAttributeMatchRule(
  inMovementSpecID:  ID,
  outMovementSpecID: ID,
  inKey:             DateAttribute,
  outKey:            DateAttribute
) extends Rule
    derives ReadWriter:

  def sql: String =
    val inAttributeFrag  = s" move_in.properties ['props'] ['${inKey.key}']"
    val outAttributeFrag = s" move_out.properties ['props'] ['${outKey.key}']"
    val inModifiedFrag   = inKey.modifySQL(inAttributeFrag)
    val outModifiedFrag  = outKey.modifySQL(outAttributeFrag)
    s""" move_in.movement_spec_id = '${inMovementSpecID.toString}'
       | and move_out.movement_spec_id = '${outMovementSpecID.toString}'
       | and $inModifiedFrag = $outModifiedFrag""".stripMargin

sealed abstract class Attribute(val key: String, val modifier: Modifier) extends Ordering[String] derives ReadWriter:
  def modifySQL(frag: String): String

case class StringAttribute(
  override val key:      String,
  override val modifier: Modifier = NoModifier
) extends Attribute(key = key, modifier = modifier):
  def modifySQL(frag: String): String = modifier.modify(frag + "::text")
  def getValue(props: Properties): String =
    props.getOrElse(key, throw IllegalArgumentException(s"property for key $key not found"))

  override def compare(x: String, y: String): Int = x.compareTo(y)

case class DateAttribute(
  override val key:      String,
  override val modifier: Modifier = NoModifier
) extends Attribute(key = key, modifier = modifier):
  def modifySQL(frag: String):     String       = modifier.modify(frag + "::text::date")
  def getValue(props: Properties): Option[Date] = props.get(key).map(DateFormat.getInstance().parse)

  override def compare(x: String, y: String): Int =
    val df = DateFormat.getInstance()
    df.parse(x).compareTo(df.parse(y))

sealed trait Modifier derives ReadWriter:
  def modify(frad: String): String

case class DateAdd(interval: Interval, value: Int) extends Modifier:
  def modify(frag: String): String = s"($frag + interval '$value $interval')::date"

case object NoModifier extends Modifier:
  def modify(frag: String): String = frag

enum Interval derives ReadWriter:
  case Year, Month, Day, Hour, Minute
