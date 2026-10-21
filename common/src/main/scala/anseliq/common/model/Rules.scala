package anseliq.common.model

import anseliq.common.model.model.Properties
import upickle.default.ReadWriter

import java.time.{LocalDate, OffsetDateTime}

enum Direction derives ReadWriter:
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
    val inAttributeFrag =
      if inKey.isConstant then s"'${inKey.key}'" else s" move_in.properties ['props'] ['${inKey.key}']"
    val outAttributeFrag =
      if outKey.isConstant then s"'${outKey.key}'" else s" move_out.properties ['props'] ['${outKey.key}']"
    val inModifiedFrag  = if inKey.isConstant then inAttributeFrag else inKey.modifySQL(inAttributeFrag)
    val outModifiedFrag = if outKey.isConstant then outAttributeFrag else outKey.modifySQL(outAttributeFrag)
    s"$inModifiedFrag = $outModifiedFrag"

case class DateAttributeMatchRule(
  inKey:  DateAttribute,
  outKey: DateAttribute
) extends Rule,
      AttributeMatcher
    derives ReadWriter:

  def sql: String =
    val inAttributeFrag =
      if inKey.isConstant then s"'${inKey.key}'::date" else s" move_in.properties ['props'] ['${inKey.key}']"
    val outAttributeFrag =
      if outKey.isConstant then s"'${outKey.key}'::date" else s" move_out.properties ['props'] ['${outKey.key}']"
    val inModifiedFrag  = if inKey.isConstant then inAttributeFrag else inKey.modifySQL(inAttributeFrag)
    val outModifiedFrag = if outKey.isConstant then outAttributeFrag else outKey.modifySQL(outAttributeFrag)
    s"$inModifiedFrag = $outModifiedFrag"

sealed abstract class Attribute[A](val key: String) extends Ordering[String]:
  def modifySQL(frag: String): String

case class StringAttribute(
  // The field name in a movement's props — or, when isConstant is true, the literal value itself
  // (matched against the other side's field on every row, rather than looked up per-row).
  override val key: String,
  val modifier:     Option[StringModifier] = None,
  val isConstant:   Boolean                = false
) extends Attribute[String](key = key),
      CanCompareProperties
    derives ReadWriter:
  def modifySQL(frag: String): String = modifier.map(_.modify(frag + "::text")).getOrElse(frag)

  override def compare(xProps: Properties, yProps: Properties): Int =
    val x = getValue(xProps)
    val y = getValue(yProps)
    x.compareTo(y)

  def getValue(props: Properties): String =
    val value =
      if isConstant then key
      else props.getOrElse(key, throw IllegalArgumentException(s"property for key $key not found"))
    modifier.map(_.apply(value)).getOrElse(value)

  override def compare(x: String, y: String): Int = x.compareTo(y)

case class DateAttribute(
  // The field name in a movement's props — or, when isConstant is true, an ISO-8601 (yyyy-MM-dd)
  // literal date, matched against the other side's field on every row rather than looked up per-row.
  override val key: String,
  val modifier:     Option[DateModifier] = None,
  val isConstant:   Boolean              = false
) extends Attribute[OffsetDateTime](key = key),
      CanCompareProperties
    derives ReadWriter:
  def modifySQL(frag: String): String = modifier.map(_.modify(frag + "::text::date")).getOrElse(frag)

  override def compare(xProps: Properties, yProps: Properties): Int =
    val x = getValue(xProps)
    val y = getValue(yProps)
    x.compareTo(y)

  def getValue(props: Properties): LocalDate =
    val dateValue =
      if isConstant then java.time.LocalDate.parse(key)
      else
        val strValue: String = props.getOrElse(key, throw IllegalArgumentException(s"property for key $key not found"))
        java.time.LocalDate.parse(strValue)
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

/**
 * Transforms a string attribute's value before the two sides are compared, e.g. to ignore the
 * formatting differences between two systems' references. apply is the Scala transformation and
 * modify the same transformation in SQL over a text expression; the reconciler compares keys built
 * by both, so they must agree on every value (StringModifierSqlSuite checks them against
 * Postgres). Any text a modifier carries is escaped before it goes into SQL, since a rule can now
 * come from a model's suggestion.
 */
sealed trait StringModifier derives ReadWriter:
  def modify(frag: String): String
  def apply(value: String): String

  /** The transformation in words around `expr`, for showing a rule to people and to a model. */
  def describe(expr: String): String

object StringModifier:
  private[model] def sqlLiteral(value: String): String = s"'${value.replace("'", "''")}'"

/** Lowercase, keeping only ASCII letters and digits: "INV-2026/0042" becomes "inv20260042". */
case object Normalize extends StringModifier:
  def modify(frag: String): String   = s"lower(regexp_replace($frag, '[^A-Za-z0-9]', '', 'g'))"
  def apply(value: String): String   = value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT)
  def describe(expr: String): String = s"normalize($expr)"

/** Removes `prefix` from the start of the value if it's there: "ACH 12345" becomes "12345". */
case class StripPrefix(prefix: String) extends StringModifier:
  def modify(frag: String): String =
    val p = StringModifier.sqlLiteral(prefix)
    s"(CASE WHEN starts_with($frag, $p) THEN substr($frag, char_length($p) + 1) ELSE $frag END)"
  def apply(value: String): String   = value.stripPrefix(prefix)
  def describe(expr: String): String = s"stripPrefix($expr, '$prefix')"

/** Removes leading zeros: "000420" becomes "420". */
case object TrimLeadingZeros extends StringModifier:
  def modify(frag: String): String   = s"ltrim($frag, '0')"
  def apply(value: String): String   = value.dropWhile(_ == '0')
  def describe(expr: String): String = s"trimLeadingZeros($expr)"

/** Applies each step in turn, so a rule can e.g. strip a prefix and then normalize. */
case class Chain(steps: List[StringModifier]) extends StringModifier:
  def modify(frag: String): String   = steps.foldLeft(frag)((f, step) => step.modify(f))
  def apply(value: String): String   = steps.foldLeft(value)((v, step) => step.apply(v))
  def describe(expr: String): String = steps.foldLeft(expr)((e, step) => step.describe(e))

/**
 * The part of the value `regex` matches: its first group if it has one, else the whole match; the
 * value unchanged if it doesn't match. Keep the regex to what Java and Postgres agree on (POSIX
 * classes, \d, groups); a regex only one of them understands breaks the parity modify promises.
 */
case class SubstringModifier(regex: String) extends StringModifier:
  def modify(frag: String): String = s"coalesce(substring($frag from ${StringModifier.sqlLiteral(regex)}), $frag)"
  def apply(value: String): String =
    val m = java.util.regex.Pattern.compile(regex).matcher(value)
    if !m.find() then value
    else if m.groupCount() > 0 then Option(m.group(1)).getOrElse(value)
    else m.group()
  def describe(expr: String): String = s"extract($expr, /$regex/)"

enum Interval derives ReadWriter:
  case Year, Month, Day, Hour, Minute
