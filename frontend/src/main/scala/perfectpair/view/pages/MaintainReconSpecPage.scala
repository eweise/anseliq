package perfectpair.view.pages

import com.raquo.laminar.api.L.*
import perfectpair.common.http.HttpPath
import perfectpair.common.model.{MovementSpecPayload, ReconRelationship, ReconciliationSpecPayload}
import perfectpair.http.ApiClient
import perfectpair.routing.Nav
import perfectpair.state.{AppState, SessionStore}
import perfectpair.view.components.{Buttons, Form, RuleWidget}
import upickle.default.*

object MaintainReconSpecPage:

  def apply(accountId: String): HtmlElement =
    AppState.activeFundFlow.now() match
      case None     => div(onMountCallback(_ => Nav.toFundFlows()))
      case Some(ff) => renderPage(accountId, ff)

  private def renderPage(accountId: String, ff: perfectpair.common.model.FundFlowPayload): HtmlElement =
    val ffId          = ff.id.getOrElse("")
    val initialSpec   = AppState.editReconSpec.now()
    val movementSpecs = Var(Seq.empty[MovementSpecPayload])
    val spec = Var(
      initialSpec.getOrElse(
        ReconciliationSpecPayload(accountId = Some(accountId))
      )
    )
    val saveBus   = EventBus[Unit]()
    val deleteBus = EventBus[Unit]()

    val inSpecIdSignal  = spec.signal.map(_.inMovementSpecId)
    val outSpecIdSignal = spec.signal.map(_.outMovementSpecId)

    val (ruleEl, ruleSignal) = RuleWidget(
      initial           = initialSpec.map(_.rule).getOrElse(perfectpair.common.model.NoOpRule),
      movementSpecs     = movementSpecs,
      inMovementSpecId  = inSpecIdSignal,
      outMovementSpecId = outSpecIdSignal
    )

    div(
      ApiClient.get[Seq[MovementSpecPayload]](
        HttpPath.url(s"${HttpPath.fundFlows}/$ffId/movement-specs"),
        SessionStore.token
      ) --> {
        case Right(specs) =>
          movementSpecs.set(specs)
          if initialSpec.isEmpty then
            val inSpec  = specs.find(_.inAccountId == accountId).flatMap(_.id).getOrElse("")
            val outSpec = specs.find(_.outAccountId == accountId).flatMap(_.id).getOrElse("")
            spec.update(_.copy(inMovementSpecId = inSpec, outMovementSpecId = outSpec))
        case Left(e) => AppState.globalError.set(Some(e))
      },
      saveBus.events.sample(ruleSignal).flatMapSwitch { rule =>
        val s     = spec.now().copy(rule = rule)
        val token = SessionStore.token
        if s.id.isDefined
        then
          ApiClient.put[ReconciliationSpecPayload, ReconciliationSpecPayload](
            HttpPath.url(s"${HttpPath.reconSpec}/${s.id.get}"),
            s,
            token
          )
        else
          ApiClient
            .post[ReconciliationSpecPayload, ReconciliationSpecPayload](HttpPath.url(HttpPath.reconSpec), s, token)
      } --> {
        case Right(_) => Nav.back()
        case Left(e)  => AppState.globalError.set(Some(e))
      },
      deleteBus.events.flatMapSwitch { _ =>
        ApiClient.delete(HttpPath.url(s"${HttpPath.reconSpec}/${spec.now().id.get}"), SessionStore.token)
      } --> {
        case Right(_) => Nav.back()
        case Left(e)  => AppState.globalError.set(Some(e))
      },
      cls := "min-h-screen bg-md-surface-variant dark:bg-gray-900 flex items-start justify-center pt-20 px-4",
      div(
        cls := "bg-white dark:bg-gray-800 rounded-xl shadow-md w-full max-w-md p-8",
        h2(cls := "text-xl font-medium text-md-on-surface dark:text-white mb-7", "Recon Spec"),
        child <-- spec.signal.map { s =>
          div(
            Form.field(
              "agingLimit",
              "Aging Limit",
              s.agingLimit.toString,
              v => spec.update(_.copy(agingLimit = v.toIntOption.getOrElse(0)))
            ),
            Form.selectField(
              "relationship",
              "Relationship",
              ReconRelationship.values.toSeq.map(r => r.toString -> r.toString),
              s.relationship.toString,
              v => spec.update(_.copy(relationship = ReconRelationship.valueOf(v)))
            ),
            child <-- movementSpecs.signal.map { specs =>
              div(
                movementSpecSelect(
                  "inMovementSpecId",
                  "In Movement Spec",
                  s.inMovementSpecId,
                  specs.filter(_.inAccountId == accountId),
                  v => spec.update(_.copy(inMovementSpecId = v))
                ),
                movementSpecSelect(
                  "outMovementSpecId",
                  "Out Movement Spec",
                  s.outMovementSpecId,
                  specs.filter(_.outAccountId == accountId),
                  v => spec.update(_.copy(outMovementSpecId = v))
                )
              )
            }
          )
        },
        ruleEl,
        div(
          cls := "flex justify-between mt-6",
          child <-- spec.signal.map { s =>
            if s.id.isDefined then
              div(
                cls := "flex gap-2",
                Buttons.textButton(
                  "Reconciliations",
                  Observer[Unit](_ =>
                    AppState.editReconSpec.set(Some(s))
                    Nav.toReconciliations(accountId)
                  )
                ),
                Buttons.textButton("Delete", Observer[Unit](_ => deleteBus.emit(())))
              )
            else div()
          },
          div(
            cls := "flex gap-2",
            Buttons.textButton("Cancel", Observer[Unit](_ => Nav.back())),
            Buttons.linkButton("Save", Observer[Unit](_ => saveBus.emit(())))
          )
        )
      )
    )

  private def movementSpecSelect(
    id:       String,
    lbl:      String,
    selected: String,
    specs:    Seq[MovementSpecPayload],
    changeFn: String => Unit
  ): HtmlElement =
    div(
      cls := "relative mb-5 border border-md-outline focus-within:border-2 focus-within:border-md-primary rounded",
      label(
        forId := id,
        cls   := "absolute -top-2.5 left-3 bg-white dark:bg-gray-800 px-1 text-xs font-medium tracking-wide text-md-on-surface-variant dark:text-gray-400",
        lbl
      ),
      select(
        idAttr := id,
        cls    := "w-full px-4 py-3.5 bg-transparent text-md-on-surface dark:text-white text-base focus:outline-none rounded",
        onMountCallback(ctx => ctx.thisNode.ref.value = selected),
        onChange.mapToValue --> Observer[String](changeFn)
      ).amend(specs.map(s => option(value := s.id.getOrElse(""), s.name))*)
    )
