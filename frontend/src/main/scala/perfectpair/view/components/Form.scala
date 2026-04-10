package perfectpair.view.components

import tyrian.Html.*
import tyrian.{Attr, EmptyAttribute, Html}

import java.time.OffsetDateTime

type FieldType = String | BigDecimal | OffsetDateTime

case class Field(value: FieldType)

object Form:

  def field[M](
    fieldID:       String,
    labelName:     String,
    defaultValue:  Option[FieldType]   = None,
    enabled:       Boolean             = true,
    onChangFn:     Option[String => M] = None,
    maybeErrorMsg: Option[String]      = None
  ): Html[M] =

    val valueAttrs: List[Attr[Nothing]] = defaultValue.map {
      case s:  String         => List(attribute("value", s), attribute("type", "text"))
      case b:  BigDecimal     => List(attribute("value", b.toString), attribute("type", "number"))
      case dt: OffsetDateTime => List(attribute("value", dt.toString), attribute("type", "datetime-local"))
    }
      .getOrElse(List())

    val allAttributes: List[Attr[M]] = valueAttrs ++ List(
      id := fieldID,
      if !enabled then attribute("disabled", "") else EmptyAttribute,
      cls := "w-full px-4 py-3.5 bg-transparent text-md-on-surface text-base focus:outline-none rounded",
      //      onChangFn.map(f => onChange(f)).getOrElse(EmptyAttribute),
      required
    )

    val hasError = maybeErrorMsg.isDefined
    val borderCls =
      if hasError then "border-2 border-md-error"
      else "border border-md-outline focus-within:border-2 focus-within:border-md-primary"
    val labelCls = if hasError then "text-md-error" else "text-md-on-surface-variant"
    div(cls := s"relative mb-5 $borderCls rounded")(
      label(
        cls := s"absolute -top-2.5 left-3 bg-white px-1 text-xs font-medium tracking-wide $labelCls",
        attribute("for", fieldID)
      )(text(labelName)),
      input(allAttributes*),
      maybeErrorMsg
        .map(errMsg => p(cls := "absolute -bottom-5 left-0 px-4 text-xs text-md-error")(text(errMsg)))
        .getOrElse(div()())
    )
