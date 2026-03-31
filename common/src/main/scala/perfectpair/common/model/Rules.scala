package perfectpair.common.model

import perfectpair.common.model.model.Properties
import upickle.ReadWriter
import upickle.default.ReadWriter.join

import java.text.DateFormat
import java.util.Date

enum Direction:
  case IN, OUT

sealed trait Rule derives ReadWriter:
  def sql: String

trait RuleCollection:
  def rules: List[Rule]

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

case class AndRule(rules: List[Rule]) extends Rule, RuleCollection:
  def sql: String = createSqlDelimiter(rules, "AND")

case class ORRule(rules: List[Rule]) extends Rule, RuleCollection:
  def sql: String = createSqlDelimiter(rules, "OR")

trait CanCompareProperties:
  def compare(xProps: Properties, yProps: Properties): Int

trait AttributeMatcher:
  def inKey:  CanCompareProperties
  def outKey: CanCompareProperties

case class StringAttributeMatchRule(
  inKey:  StringAttribute,
  outKey: StringAttribute
) extends Rule,
      AttributeMatcher
    derives ReadWriter:

  def sql: String =
    val inAttributeFrag  = s" move_in.properties ['props'] ['${inKey.key}']"
    val outAttributeFrag = s" move_out.properties ['props'] ['${outKey.key}']"
    val inModifiedFrag   = inKey.modifySQL(inAttributeFrag)
    val outModifiedFrag  = outKey.modifySQL(outAttributeFrag)
    s""" move_in.movement_spec_id = ''
       | and move_out.movement_spec_id = ''
       | and $inModifiedFrag = $outModifiedFrag""".stripMargin

case class DateAttributeMatchRule(
  inKey:  DateAttribute,
  outKey: DateAttribute
) extends Rule,
      AttributeMatcher
    derives ReadWriter:

  def sql: String =
    val inAttributeFrag  = s" move_in.properties ['props'] ['${inKey.key}']"
    val outAttributeFrag = s" move_out.properties ['props'] ['${outKey.key}']"
    val inModifiedFrag   = inKey.modifySQL(inAttributeFrag)
    val outModifiedFrag  = outKey.modifySQL(outAttributeFrag)
    s""" move_in.movement_spec_id = ''
       | and move_out.movement_spec_id = ''
       | and $inModifiedFrag = $outModifiedFrag""".stripMargin

sealed abstract class Attribute(val key: String, val modifier: Modifier) extends Ordering[String] derives ReadWriter:
  def modifySQL(frag: String): String

case class StringAttribute(
  override val key:      String,
  override val modifier: Modifier = NoModifier
) extends Attribute(key = key, modifier = modifier),
      CanCompareProperties:
  def modifySQL(frag: String): String = modifier.modify(frag + "::text")

  override def compare(xProps: Properties, yProps: Properties): Int =
    val x = getValue(xProps)
    val y = getValue(xProps)
    x.compareTo(y)

  def getValue(props: Properties): String =
    props.getOrElse(key, throw IllegalArgumentException(s"property for key $key not found"))

  override def compare(x: String, y: String): Int = x.compareTo(y)

case class DateAttribute(
  override val key:      String,
  override val modifier: Modifier = NoModifier
) extends Attribute(key = key, modifier = modifier),
      CanCompareProperties:
  def modifySQL(frag: String): String = modifier.modify(frag + "::text::date")

  override def compare(xProps: Properties, yProps: Properties): Int =
    val x = getValue(xProps)
    val y = getValue(xProps)
    x.compareTo(y)

  def getValue(props: Properties): Date =
    val strValue: String = props.getOrElse(key, throw IllegalArgumentException(s"property for key $key not found"))
    DateFormat.getInstance().parse(strValue)

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
