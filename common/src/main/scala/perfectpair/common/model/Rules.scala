package perfectpair.common.model

import perfectpair.common.model.model.Properties
import upickle.default.ReadWriter

import java.time.{LocalDate, OffsetDateTime}

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

case object NoOpRule extends Rule:
  def sql: String = ""


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

sealed abstract class Attribute[A](val key: String) extends Ordering[String]:
  def modifySQL(frag: String): String

case class StringAttribute(
  override val key: String,
  val modifier:     Option[StringModifier] = None
) extends Attribute[String](key = key),
      CanCompareProperties
    derives ReadWriter:
  def modifySQL(frag: String): String = modifier.map(_.modify(frag + "::text")).getOrElse(frag)

  override def compare(xProps: Properties, yProps: Properties): Int =
    val x = getValue(xProps)
    val y = getValue(yProps)
    x.compareTo(y)

  def getValue(props: Properties): String =
    val value = props.getOrElse(key, throw IllegalArgumentException(s"property for key $key not found"))
    modifier.map(_.apply(value)).getOrElse(value)

  override def compare(x: String, y: String): Int = x.compareTo(y)

case class DateAttribute(
  override val key: String,
  val modifier:     Option[DateModifier] = None
) extends Attribute[OffsetDateTime](key = key),
      CanCompareProperties
    derives ReadWriter:
  def modifySQL(frag: String): String = modifier.map(_.modify(frag + "::text::date")).getOrElse(frag)

  override def compare(xProps: Properties, yProps: Properties): Int =
    val x = getValue(xProps)
    val y = getValue(xProps)
    x.compareTo(y)

  def getValue(props: Properties): LocalDate =
    val strValue: String = props.getOrElse(key, throw IllegalArgumentException(s"property for key $key not found"))
    val dateValue = java.time.LocalDate.parse(strValue)
    modifier.map(_.apply(dateValue)).getOrElse(dateValue)

  override def compare(x: String, y: String): Int =
    LocalDate.parse(x).compareTo(LocalDate.parse(y))

//todo make a datetime modifier
sealed trait DateModifier derives ReadWriter:
  def modify(frag: String):    String
  def apply(value: LocalDate): LocalDate

case class DateAdd(interval: Interval, value: Int) extends DateModifier:
  def modify(frag:      String):    String    = s"($frag + interval '$value $interval')::date"
  override def apply(d: LocalDate): LocalDate = d.plusDays(value)

sealed trait StringModifier derives ReadWriter:
  def modify(frag: String): String
  def apply(value: String): String

case class SubstringModifier(regex: String) extends StringModifier:
  def modify(frag:      String): String = frag
  override def apply(d: String): String = ???

enum Interval derives ReadWriter:
  case Year, Month, Day, Hour, Minute
