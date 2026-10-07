package anseliq.domain.service

import anseliq.common.model.*
import anseliq.domain.service.parser.{BAIParser, FixedWidthParser, JSONParser, ParsedLine, Parser, ParserError}

class ParserSuite extends munit.FunSuite:

  // Feeds each line through the parser in order (matching how ingestResumable's Flow calls parse
  // once per line, sequentially) and collects every emitted transaction, in emission order.
  private def parseAll(parser: Parser, lines: Seq[String]): Seq[ParsedLine] =
    lines.zipWithIndex.flatMap((line, idx) => parser.parse(line, idx))

  private val sampleFile = Seq(
    "01,SENDERID,RECEIVERID,250101,0800,1,80,,2/",
    "02,RECEIVERID,SENDERID,1,250101,0800,USD,/",
    "03,1234567,USD,010,500000,,,/",
    "16,195,150000,,BANKREF001,CUSTREF001,Wire transfer from ACME Corp",
    "88,continued text",
    "16,475,25000,,BANKREF002,CUSTREF002,Check deposit",
    "49,175000,3/",
    "98,175000,1,5/",
    "99,175000,1,7/"
  )

  test("BAIParser extracts transaction detail records, deferred by one line to allow for continuations"):
    val results = parseAll(BAIParser(), sampleFile)
    assertEquals(results.size, 2, "01/02/03/88/49/98/99 lines must never themselves produce output")

    val txn1 = results(0)
    assertEquals(txn1.maybeAmount, Some(BigDecimal(1500)))
    assertEquals(txn1.props("account_number"), "1234567")
    assertEquals(txn1.props("type_code"), "195")
    assertEquals(txn1.props("bank_reference_number"), "BANKREF001")
    assertEquals(txn1.props("customer_reference_number"), "CUSTREF001")
    assertEquals(
      txn1.props("text"),
      "Wire transfer from ACME Corpcontinued text",
      "continuation text is concatenated with no separator, matching a line artificially split at a length limit"
    )

    val txn2 = results(1)
    assertEquals(txn2.maybeAmount, Some(BigDecimal(250)))
    assertEquals(txn2.props("account_number"), "1234567")
    assertEquals(txn2.props("bank_reference_number"), "BANKREF002")
    assertEquals(txn2.props("customer_reference_number"), "CUSTREF002")
    assertEquals(txn2.props("text"), "Check deposit")

  test("BAIParser carries the most recent account number forward into every transaction until a new 03 record"):
    val lines = Seq(
      "03,ACCOUNT-A,USD,010,100000,,,/",
      "16,195,100000,,REF-A,REF-A,txn on account A",
      "03,ACCOUNT-B,USD,010,200000,,,/",
      "16,195,200000,,REF-B,REF-B,txn on account B",
      "49,300000,4/"
    )
    val results = parseAll(BAIParser(), lines)
    assertEquals(
      results.size,
      2,
      "the new 03 record must flush account A's pending transaction before switching context"
    )
    assertEquals(results(0).props("account_number"), "ACCOUNT-A")
    assertEquals(results(1).props("account_number"), "ACCOUNT-B")

  test("BAIParser throws ParserError on a malformed amount"):
    intercept[ParserError]:
      BAIParser().parse("16,195,not-a-number,,REF,REF,bad amount", 0)

  test(
    "BAIParser drops a final transaction with no trailer to flush it (known limitation of well-formed-file assumption)"
  ):
    val lines = Seq(
      "03,ACCOUNT-A,USD,010,100000,,,/",
      "16,195,100000,,REF-A,REF-A,never flushed"
    )
    val results = parseAll(BAIParser(), lines)
    assert(results.isEmpty, "no 49/98/99 trailer ever arrives to flush the pending transaction")

  test("BAIParser only stores the fields selected in fieldMappings, under the configured names"):
    val config = BAIParserConfig(
      fieldMappings = Map(
        BAIField.CustomerReferenceNumber -> "ref",
        BAIField.AccountNumber           -> "account"
      )
    )
    val results = parseAll(BAIParser(config), sampleFile)
    assertEquals(results.size, 2)

    val txn1 = results(0)
    assertEquals(txn1.props, Map("ref" -> "CUSTREF001", "account" -> "1234567"))
    // Amount is always captured regardless of fieldMappings — it isn't a selectable field.
    assertEquals(txn1.maybeAmount, Some(BigDecimal(1500)))

  test("BAIParser drops continuation text entirely when Text isn't a selected field"):
    val config  = BAIParserConfig(fieldMappings = Map(BAIField.CustomerReferenceNumber -> "ref"))
    val results = parseAll(BAIParser(config), sampleFile)
    assertEquals(results.size, 2)
    assertEquals(
      results(0).props,
      Map("ref" -> "CUSTREF001"),
      "no 'text' key at all, and no error from the 88 continuation line with nothing to extend"
    )

  test("BAIParser with an empty fieldMappings stores no properties but still captures amount"):
    val results = parseAll(BAIParser(BAIParserConfig(fieldMappings = Map.empty)), sampleFile)
    assertEquals(results.size, 2)
    assertEquals(results(0).props, Map.empty[String, String])
    assertEquals(results(0).maybeAmount, Some(BigDecimal(1500)))

  test("JSONParser extracts configured fields by JSONPath, under the configured property names"):
    val config = JSONParserConfig(
      fieldMappings = Map(
        "$.transaction.customerRef" -> "customer_ref",
        "$.description"             -> "text"
      ),
      amountPath = Some("$.transaction.amount")
    )
    val line =
      """{"transaction": {"customerRef": "CUST-001", "amount": 150.75}, "description": "Wire transfer"}"""

    val Some(result) = JSONParser(config).parse(line, 0): @unchecked
    assertEquals(result.props, Map("customer_ref" -> "CUST-001", "text" -> "Wire transfer"))
    assertEquals(result.maybeAmount, Some(BigDecimal("150.75")))

  test("JSONParser treats a JSONPath that matches nothing on a given row as absent, not an error"):
    val config = JSONParserConfig(fieldMappings = Map("$.optionalField" -> "opt"))
    val line   = """{"other": "value"}"""

    val Some(result) = JSONParser(config).parse(line, 0): @unchecked
    assertEquals(result.props, Map.empty[String, String])

  test("JSONParser returns None for a blank line"):
    assertEquals(JSONParser(JSONParserConfig()).parse("   ", 0), None)

  test("JSONParser throws ParserError on malformed JSON"):
    intercept[ParserError]:
      JSONParser(JSONParserConfig()).parse("{not valid json", 0)

  test("JSONParser throws ParserError when the amountPath value isn't a valid number"):
    val config = JSONParserConfig(amountPath = Some("$.amount"))
    intercept[ParserError]:
      JSONParser(config).parse("""{"amount": "not-a-number"}""", 0)

  test("JSONParser with no amountPath configured never sets an amount"):
    val config       = JSONParserConfig(fieldMappings = Map("$.id" -> "id"))
    val Some(result) = JSONParser(config).parse("""{"id": "abc", "amount": 100}""", 0): @unchecked
    assertEquals(result.maybeAmount, None)

  private val fixedWidthLine = "ACME CORP 0001500.00WIRE TRANSFER"
  //                            123456789012345678901234567890123
  //                            1        10       20       30

  test("FixedWidthParser extracts fields by 1-based inclusive position, trimming padding"):
    val config = FixedWidthParserConfig(
      fields = Seq(
        FixedWidthField("name", 1, 10),
        FixedWidthField("id", 11, 14),
        FixedWidthField("amount", 15, 20, isAmount = true),
        FixedWidthField("text", 21, 33)
      )
    )
    val Some(result) = FixedWidthParser(config).parse(fixedWidthLine, 0): @unchecked
    assertEquals(result.props("name"), "ACME CORP")
    assertEquals(result.props("id"), "0001")
    assertEquals(result.props("text"), "WIRE TRANSFER")
    assertEquals(result.maybeAmount, Some(BigDecimal("500.00")))

  test("FixedWidthParser skips the first line when hasHeader is true"):
    val config = FixedWidthParserConfig(hasHeader = true, fields = Seq(FixedWidthField("name", 1, 10)))
    val parser = FixedWidthParser(config)
    assertEquals(parser.parse("HEADER LINE HERE", 0), None)
    assertEquals(parser.parse(fixedWidthLine, 1).map(_.props("name")), Some("ACME CORP"))

  test("FixedWidthParser returns None for a blank line"):
    assertEquals(FixedWidthParser(FixedWidthParserConfig()).parse("   ", 0), None)

  test("FixedWidthParser clamps a field range past the end of a short line instead of throwing"):
    val config       = FixedWidthParserConfig(fields = Seq(FixedWidthField("name", 1, 10), FixedWidthField("tail", 5, 50)))
    val Some(result) = FixedWidthParser(config).parse("AB", 0): @unchecked
    assertEquals(result.props("name"), "AB")
    assertEquals(result.props("tail"), "")

  test("FixedWidthParser throws ParserError on a malformed amount"):
    val config = FixedWidthParserConfig(fields = Seq(FixedWidthField("amount", 1, 10, isAmount = true)))
    intercept[ParserError]:
      FixedWidthParser(config).parse("not-a-num!", 0)

  test("FixedWidthParser with no field marked isAmount never sets an amount"):
    val config       = FixedWidthParserConfig(fields = Seq(FixedWidthField("name", 1, 10)))
    val Some(result) = FixedWidthParser(config).parse(fixedWidthLine, 0): @unchecked
    assertEquals(result.maybeAmount, None)
