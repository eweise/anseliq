package onparity.view.components

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import onparity.http.Chart
import onparity.state.AppState

import scala.scalajs.js

object ChartCanvas:

  case class Series(labels: Seq[String], values: Seq[Double])

  private val paletteLight = js.Array("#6d28d9", "#0891b2", "#d97706", "#059669", "#db2777", "#6366f1")
  private val paletteDark  = js.Array("#a78bfa", "#22d3ee", "#fbbf24", "#34d399", "#f472b6", "#818cf8")

  def apply(chartType: String, dataSignal: Signal[Series], horizontal: Boolean = false): HtmlElement =
    var chartInstance: Option[Chart] = None

    def render(canvas: dom.HTMLCanvasElement, series: Series, dark: Boolean): Unit =
      chartInstance.foreach(_.destroy())

      val textColor = if dark then "#e5e7eb" else "#374151"
      // #e5e7eb (a very pale gray) was previously used for light mode too, nearly invisible
      // against the page's white background — #9ca3af has enough contrast to actually show.
      val gridColor = if dark then "#374151" else "#9ca3af"
      val colors    = if dark then paletteDark else paletteLight

      val scalesConfig: js.Any =
        if chartType == "doughnut" then js.undefined
        else
          js.Dynamic.literal(
            x = js.Dynamic
              .literal(ticks = js.Dynamic.literal(color = textColor), grid = js.Dynamic.literal(color = gridColor)),
            y = js.Dynamic
              .literal(ticks = js.Dynamic.literal(color = textColor), grid = js.Dynamic.literal(color = gridColor))
          )

      // A legend only earns its keep with multiple series to distinguish (the doughnut's categories);
      // a single-series bar chart's one dataset has no name worth labeling, so Chart.js would only
      // show a stray "undefined" swatch for it.
      val legendConfig =
        if chartType == "doughnut" then
          js.Dynamic.literal(display    = true, labels = js.Dynamic.literal(color = textColor))
        else js.Dynamic.literal(display = false)

      // Bar/doughnut datasets are one series per category, so each value gets its own swatch from
      // the palette array and needs no visible border. A line chart is the opposite: one series
      // across time, so it needs a single-color stroke (borderColor/borderWidth) to actually draw
      // the connecting line — leaving borderWidth at 0 (the bar/doughnut default) makes the line
      // invisible and leaves only a scatter of rainbow-colored points.
      val dataset =
        if chartType == "line" then
          js.Dynamic.literal(
            data                 = js.Array(series.values.map(v => v: js.Any)*),
            borderColor          = colors(0),
            backgroundColor      = colors(0),
            pointBackgroundColor = colors(0),
            borderWidth          = 2,
            pointRadius          = 3,
            tension              = 0.2,
            fill                 = false
          )
        else
          js.Dynamic.literal(
            data            = js.Array(series.values.map(v => v: js.Any)*),
            backgroundColor = colors,
            borderWidth     = 0
          )

      val config = js.Dynamic.literal(
        `type` = chartType,
        data = js.Dynamic.literal(
          labels   = js.Array(series.labels*),
          datasets = js.Array(dataset)
        ),
        options = js.Dynamic.literal(
          responsive = true,
          indexAxis  = if horizontal then "y" else "x",
          plugins    = js.Dynamic.literal(legend = legendConfig),
          scales     = scalesConfig
        )
      )
      chartInstance = Some(new Chart(canvas.getContext("2d"), config))

    canvasTag(
      heightAttr := 220,
      inContext { el =>
        dataSignal
          .combineWithFn(AppState.darkMode.signal)((series, dark) => (series, dark)) --> Observer[(Series, Boolean)] {
          case (series, dark) => render(el.ref, series, dark)
        }
      },
      onUnmountCallback(_ => chartInstance.foreach(_.destroy()))
    )
