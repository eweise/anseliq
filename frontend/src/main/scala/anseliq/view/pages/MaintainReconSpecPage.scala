package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.{
  AccountPayload,
  FileFeedPayload,
  MovementSpecPayload,
  ORRule,
  ReconRelationship,
  ReconciliationSpecPayload,
  Rule,
  RuleProposalPayload
}
import anseliq.http.ApiClient
import anseliq.routing.Nav
import anseliq.state.AppState.apiBind
import anseliq.state.{AppState, SessionStore}
import anseliq.view.components.{Buttons, Form, RuleWidget}
import upickle.default.*

// The recon rule form (relationship, aging limit, in/out movement specs, match rule). Rendered in a
// Modal by the ledger page's Setup tab — the same in-page pattern as the account and movement-spec
// forms — rather than as its own route; `onClose` runs after Save, Delete or Cancel.
object MaintainReconSpecPage:

  def apply(accountId: String, reconSpecId: String, onClose: () => Unit): HtmlElement =
    AppState.activeLedger.now() match
      case None     => div(onMountCallback(_ => onClose()))
      case Some(ff) => renderPage(accountId, reconSpecId, ff, onClose)

  private def renderPage(
    accountId:   String,
    reconSpecId: String,
    ff:          anseliq.common.model.LedgerPayload,
    onClose:     () => Unit
  ): HtmlElement =
    val ffId      = ff.id.getOrElse("")
    val loadState = Var[LoadState](if reconSpecId == "new" then LoadState.Ready(None) else LoadState.Loading)
    val loadBus   = EventBus[Unit]()

    div(
      loadBus.events
        .flatMapSwitch(_ =>
          ApiClient.get[Seq[ReconciliationSpecPayload]](
            HttpPath.url(s"${HttpPath.account}/$accountId/recon-specs"),
            SessionStore.token
          )
        )
        .apiBind(specs => loadState.set(LoadState.Ready(specs.find(_.id.contains(reconSpecId))))),
      onMountCallback(_ => if reconSpecId != "new" then loadBus.emit(())),
      child <-- loadState.signal.map {
        case LoadState.Loading            => span(cls := "text-md-on-surface-variant", "Loading…")
        case LoadState.Ready(initialSpec) => renderForm(accountId, ffId, initialSpec, onClose)
      }
    )

  private def renderForm(
    accountId:   String,
    ffId:        String,
    initialSpec: Option[ReconciliationSpecPayload],
    onClose:     () => Unit
  ): HtmlElement =
    val movementSpecs = Var(Seq.empty[MovementSpecPayload])
    val fileFeeds     = Var(Seq.empty[FileFeedPayload])
    val accounts      = Var(Seq.empty[AccountPayload])
    val spec = Var(
      initialSpec.getOrElse(
        ReconciliationSpecPayload(accountId = Some(accountId))
      )
    )
    val saveBus        = EventBus[Unit]()
    val deleteBus      = EventBus[Unit]()
    val addAlternative = EventBus[Rule]()
    val proposals      = Var(Seq.empty[RuleProposalPayload])

    val inSpecIdSignal  = spec.signal.map(_.inMovementSpecId)
    val outSpecIdSignal = spec.signal.map(_.outMovementSpecId)

    val (ruleEl, ruleSignal) = RuleWidget(
      initial           = initialSpec.map(_.rule).getOrElse(anseliq.common.model.NoOpRule),
      movementSpecs     = movementSpecs,
      fileFeeds         = fileFeeds,
      inMovementSpecId  = inSpecIdSignal,
      outMovementSpecId = outSpecIdSignal,
      accounts          = accounts.signal,
      addAlternative    = addAlternative.events
    )

    div(
      ApiClient
        .get[Seq[MovementSpecPayload]](
          HttpPath.url(s"${HttpPath.ledgers}/$ffId/movement-specs"),
          SessionStore.token
        )
        .apiBind { specs =>
          movementSpecs.set(specs)
          if initialSpec.isEmpty then
            val inSpec  = specs.find(_.inAccountId == accountId).flatMap(_.id).getOrElse("")
            val outSpec = specs.find(_.outAccountId == accountId).flatMap(_.id).getOrElse("")
            spec.update(_.copy(inMovementSpecId = inSpec, outMovementSpecId = outSpec))
        },
      ApiClient
        .get[Seq[FileFeedPayload]](HttpPath.url(HttpPath.fileFeeds), SessionStore.token)
        .apiBind(feeds => fileFeeds.set(feeds)),
      ApiClient
        .get[Seq[AccountPayload]](HttpPath.url(s"${HttpPath.ledgers}/$ffId/accounts"), SessionStore.token)
        .apiBind(accounts.set),
      initialSpec.flatMap(_.id).map { id =>
        ApiClient
          .get[Seq[RuleProposalPayload]](HttpPath.url(s"${HttpPath.reconSpec}/$id/rule-proposals"), SessionStore.token)
          .apiBind(proposals.set)
      },
      saveBus.events
        .sample(ruleSignal)
        .flatMapSwitch { rule =>
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
        }
        .apiBind(_ => onClose()),
      deleteBus.events.flatMapSwitch { _ =>
        ApiClient.delete(HttpPath.url(s"${HttpPath.reconSpec}/${spec.now().id.get}"), SessionStore.token)
      }.apiBind(_ => onClose()),
      div(
        h2(cls := "text-xl font-medium text-md-on-surface dark:text-white mb-7", "Reconciliation rule"),
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
                  "Primary movement spec",
                  s.inMovementSpecId,
                  specs.filter(_.inAccountId == accountId),
                  v => spec.update(_.copy(inMovementSpecId = v))
                ),
                movementSpecSelect(
                  "outMovementSpecId",
                  "Secondary movement spec",
                  s.outMovementSpecId,
                  specs.filter(_.outAccountId == accountId),
                  v => spec.update(_.copy(outMovementSpecId = v))
                )
              )
            }
          )
        },
        ruleEl,
        child <-- proposals.signal.combineWith(ruleSignal).map(learnedRules(_, _, addAlternative)),
        div(
          cls := "flex justify-between mt-6",
          child <-- spec.signal.map { s =>
            if s.id.isDefined then
              div(
                cls := "flex gap-2",
                Buttons.textButton("Delete", Observer[Unit](_ => deleteBus.emit(())))
              )
            else div()
          },
          div(
            cls := "flex gap-2",
            Buttons.textButton("Cancel", Observer[Unit](_ => onClose())),
            Buttons.linkButton("Save", Observer[Unit](_ => saveBus.emit(())))
          )
        )
      )
    )

  // Rules learned from this spec's accepted match suggestions that its rule doesn't include yet.
  // Adding one puts it in the rule above as an alternative; Save persists it.
  private def learnedRules(
    proposals:      Seq[RuleProposalPayload],
    rule:           Rule,
    addAlternative: EventBus[Rule]
  ): HtmlElement =
    val current = rule match
      case ORRule(rules) => rules.toSet
      case other         => Set(other)
    val notAdded = proposals.filterNot(p => current.contains(p.rule))
    if notAdded.isEmpty then div()
    else
      div(
        cls := "border-t border-md-outline pt-5 mt-5",
        p(cls := "text-sm font-medium text-md-on-surface dark:text-gray-200 mb-1", "Learned from accepted suggestions"),
        p(
          cls := "text-xs text-md-on-surface-variant dark:text-gray-400 mb-3",
          "Rules that explained matches people accepted. Add one so those pairs match without a suggestion from now on."
        ),
        ul(
          cls := "space-y-3",
          notAdded.map { proposal =>
            li(
              cls := "flex items-center justify-between gap-4",
              div(
                code(cls := "font-mono text-xs text-md-on-surface dark:text-gray-200", proposal.condition),
                div(
                  cls := "text-xs text-md-on-surface-variant dark:text-gray-400",
                  s"Explained ${proposal.acceptedSuggestions} accepted suggestions · would match ${proposal.unmatchedPairs} unmatched pairs now"
                )
              ),
              button(
                cls := "shrink-0 text-xs text-md-primary border border-md-primary rounded-full px-3 py-1",
                typ := "button",
                onClick --> (_ => addAlternative.emit(proposal.rule)),
                "Add as alternative"
              )
            )
          }
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

  private enum LoadState:
    case Loading
    case Ready(spec: Option[ReconciliationSpecPayload])
