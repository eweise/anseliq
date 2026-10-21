package anseliq.domain.model

import anseliq.common.model.*
import anseliq.testing.TestAppContainer
import com.augustnagro.magnum.{DbCon, transact}

// The reconciler compares match keys built by a StringModifier's SQL (modify) with ones built by
// its Scala (apply), so the two must agree on every value. Runs each modifier's SQL in Postgres.
class StringModifierSqlSuite extends munit.FunSuite:
  private val app = TestAppContainer()

  private val values = Seq(
    "INV-2026-0042",
    "inv20260042",
    "PO 7781/A",
    "ACH 12345",
    "ACHACH 1",
    "000420",
    "000",
    "0",
    "",
    "it's",
    "a'b--c;d",
    "Ünïcødé-12",
    "  spaced  ",
    "100.50"
  )

  private val modifiers = Seq(
    Normalize,
    StripPrefix("ACH "),
    StripPrefix("it'"),
    StripPrefix(""),
    TrimLeadingZeros,
    Chain(List(StripPrefix("ACH "), TrimLeadingZeros)),
    Chain(List(Normalize, TrimLeadingZeros)),
    SubstringModifier("[0-9]+"),
    SubstringModifier("INV-([0-9]+)-"),
    SubstringModifier("x(y)?")
  )

  private def literal(value: String) = s"'${value.replace("'", "''")}'"

  private def inSql(m: StringModifier, value: String)(using con: DbCon): Option[String] =
    val result = con.connection.createStatement().executeQuery(s"SELECT ${m.modify(literal(value))}")
    result.next()
    Option(result.getString(1))

  modifiers.foreach: m =>
    test(s"${m.describe("x")} gives the same result in Scala and SQL"):
      transact(app.transactor):
        values.foreach(v => assertEquals(inSql(m, v), Some(m.apply(v)), s"value '$v'"))

  test("text a modifier carries can't break out of its SQL string"):
    val hostile = StripPrefix("'; DROP TABLE recon.movement; --")
    transact(app.transactor):
      assertEquals(inSql(hostile, "unchanged"), Some("unchanged"))
      assertEquals(inSql(hostile, "'; DROP TABLE recon.movement; --rest"), Some("rest"))

  test("Normalize makes differently formatted references equal"):
    assertEquals(Normalize("INV-2026-0042"), Normalize("inv20260042"))
    assertEquals(Normalize("PO 7781/A"), Normalize("PO7781A"))

  test("a rule's description shows its modifiers"):
    val rule = StringAttributeMatchRule(
      StringAttribute("transaction_id", Some(Chain(List(StripPrefix("ACH "), Normalize)))),
      StringAttribute("txn_id", Some(Normalize))
    )
    assertEquals(RuleMatch(rule, "").condition, "normalize(stripPrefix(transaction_id, 'ACH ')) = normalize(txn_id)")
