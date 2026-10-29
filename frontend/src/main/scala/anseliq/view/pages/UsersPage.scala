package anseliq.view.pages

import com.raquo.laminar.api.L.*
import anseliq.common.http.HttpPath
import anseliq.common.model.UserPayload
import anseliq.http.ApiClient
import anseliq.state.AppState.apiBind
import anseliq.state.SessionStore
import anseliq.view.components.{Buttons, Modal, PageHeader, Table}

object UsersPage:

  def apply(): HtmlElement =
    val users = Var(Seq.empty[UserPayload])
    // In-page Modal (not a route navigation) so the users list stays mounted underneath, matching
    // LedgerPage's account/movement-spec edit pattern. None = no modal open.
    val editingUserId = Var(Option.empty[String])
    val refreshBus    = EventBus[Unit]()
    val closeModal = () => {
      editingUserId.set(None)
      refreshBus.emit(())
    }

    PageHeader.page(
      ApiClient.get[Seq[UserPayload]](HttpPath.users, SessionStore.token).apiBind(users.set),
      refreshBus.events.flatMapSwitch { _ =>
        ApiClient.get[Seq[UserPayload]](HttpPath.users, SessionStore.token)
      }.apiBind(users.set),
      PageHeader.titleRow(
        cls := "flex justify-between items-center",
        PageHeader.title("Users & Roles"),
        Buttons.linkButton("Add User", Observer[Unit](_ => editingUserId.set(Some("new"))))
      ),
      div(
        cls := "w-full px-8",
        child <-- users.signal.map { us =>
          Table.create(
            Seq("Name", "Email", "Role", "Active", ""),
            us.sortBy(u => (u.firstName, u.lastName)).map { u =>
              Seq(
                span(s"${u.firstName} ${u.lastName}"),
                span(u.email),
                span(u.role),
                span(if u.active then "Yes" else "No"),
                button(
                  cls := "text-md-primary hover:underline",
                  onClick --> (_ => editingUserId.set(Some(u.id.getOrElse("new")))),
                  "Edit"
                )
              )
            }
          )
        }
      ),
      child.maybe <-- editingUserId.signal.map(_.map(id => Modal(closeModal, MaintainUserPage(id, closeModal))))
    )
