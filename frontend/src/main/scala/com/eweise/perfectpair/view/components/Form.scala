package com.eweise.perfectpair.view.components

import tyrian.Html
import tyrian.Html.*

object Form:
  def field[M](
    fieldID: String,
    labelName: String,
    onChangFn: String => M,
    maybeErrorMsg: Option[String] = None
  ): Html[M] =
    div(cls := "mb-5")(
      label(
        cls :=
          """block mb-2.5
            |text-sm
            |text-fg-danger-strong
            |font-medium
            |text-heading""".stripMargin
      )(text(labelName)),
      input(
        id := fieldID,
        cls :=
          """bg-neutral-secondary-medium
            |border border-default-medium
            |text-heading
            |text-sm
            |rounded-base
            |focus:ring-brand
            |focus:border-brand
            |block
            |w-full
            |px-3
            |py-2.5
            |shadow-xs
            |placeholder:text-body""".stripMargin,
        onChange(onChangFn),
        required
      ),
      maybeErrorMsg
        .map(errMsg =>
          p(
            cls :=
              """mt-2.5
                |text-sm
                |text-fg-danger-strong""".stripMargin
          )(span(cls := "font-medium")("Error"), text(errMsg))
        )
        .getOrElse(div()())
    )
