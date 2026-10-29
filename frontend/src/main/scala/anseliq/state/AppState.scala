package anseliq.state

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import anseliq.common.model.LedgerPayload

object AppState:
  val darkMode:     Var[Boolean]               = Var(dom.window.localStorage.getItem("darkMode") == "true")
  val globalError:  Var[Option[String]]        = Var(None)
  val ledgers:      Var[Seq[LedgerPayload]]    = Var(Seq.empty)
  val activeLedger: Var[Option[LedgerPayload]] = Var(None)

  def toggleDarkMode(): Unit =
    darkMode.update(!_)
    dom.window.localStorage.setItem("darkMode", darkMode.now().toString)

  // Every ApiClient call site used to repeat `stream --> { case Right(a) => ...; case Left(e) =>
  // AppState.globalError.set(Some(e)) }` by hand — dozens of times across the page files. This
  // collapses that into `stream.apiBind(a => ...)`. Defined as an extension (not a plain method
  // taking the stream as a param) specifically so A is inferred from the stream's own concrete
  // type instead of from the onSuccess closure — inference the other way around fails, since
  // apiObserver-as-a-plain-method has nothing but an unconstrained closure to solve A from until
  // after --> has already been type-checked. Only fits pages that want the standard "show it in
  // the global error banner" failure handling — a page with different failure UX (e.g.
  // LoginPage's inline form error) should keep its own match instead.
  extension [A](stream: EventStream[Either[String, A]])
    def apiBind(onSuccess: A => Unit) =
      stream --> Observer[Either[String, A]] {
        // Clearing on Right, not just setting on Left, matters for retries: without this, fixing
        // whatever caused the error and retrying the same action left the old banner stuck on
        // screen even though the retry just succeeded.
        case Right(a) =>
          globalError.set(None)
          onSuccess(a)
        case Left(e) => globalError.set(Some(e))
      }
