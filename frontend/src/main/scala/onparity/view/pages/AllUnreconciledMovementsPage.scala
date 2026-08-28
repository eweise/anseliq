package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.UnreconciledMovementPayload
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.SessionStore
import onparity.view.components.{PageHeader, Pill, Table}

// The Dashboard's aging chart aggregates unreconciled movements across every account/fund flow,
// unlike UnreconciledMovementsPage (which is scoped to one account) — this is the drill-through
// target for that chart, listing every row /reports/unreconciled-movements returns with no
// account/fund-flow filter, so a bucket's count on the chart always matches the row count here.
object AllUnreconciledMovementsPage:

  def apply(): HtmlElement =
    val movements = Var(Seq.empty[UnreconciledMovementPayload])

    PageHeader.page(
      ApiClient
        .get[Seq[UnreconciledMovementPayload]](
          HttpPath.url(s"${HttpPath.reports}/unreconciled-movements"),
          SessionStore.token
        )
        .apiBind(ms => movements.set(ms)),
      PageHeader.titleRow(
        PageHeader.backLink(Observer[Unit](_ => Nav.back())),
        PageHeader.title("Unreconciled Movements")
      ),
      div(
        cls := "w-full px-8",
        child <-- movements.signal.map { ms =>
          Table.create(
            Seq("Account", "Reference ID", "Amount", "Age", "Aging Bucket"),
            ms.map(renderRow),
            numCols = Set(2)
          )
        }
      )
    )

  private def bucketTone(bucket: String): Pill.Tone = bucket match
    case "0-3"  => Pill.Tone.Good
    case "4-7"  => Pill.Tone.Warning
    case "8-14" => Pill.Tone.Serious
    case _      => Pill.Tone.Critical

  private def renderRow(m: UnreconciledMovementPayload): Seq[HtmlElement] =
    Seq(
      span(m.accountName),
      a(
        href := s"/components/movements/${m.movementId}",
        cls  := "text-md-primary hover:underline cursor-pointer",
        onClick --> { e =>
          e.preventDefault()
          Nav.toMovementDetail(m.movementId)
        },
        m.referenceId
      ),
      span(m.amount.toString),
      span(s"${m.ageDays}d"),
      Pill(m.agingBucket, bucketTone(m.agingBucket))
    )
