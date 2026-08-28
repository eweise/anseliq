package onparity.view.pages

import com.raquo.laminar.api.L.*
import onparity.common.http.HttpPath
import onparity.common.model.UserPayload
import onparity.http.ApiClient
import onparity.routing.Nav
import onparity.state.AppState.apiBind
import onparity.state.SessionStore
import onparity.view.components.{Buttons, PageHeader, Table}

object UsersPage:

  def apply(): HtmlElement =
    val users = Var(Seq.empty[UserPayload])

    PageHeader.page(
      ApiClient.get[Seq[UserPayload]](HttpPath.url(HttpPath.users), SessionStore.token).apiBind(users.set),
      PageHeader.titleRow(
        cls := "flex justify-between items-center",
        PageHeader.title("Users & Roles"),
        Buttons.linkButton("Add User", Observer[Unit](_ => Nav.toMaintainUser()))
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
                a(
                  href := s"/components/maintain-user/${u.id.getOrElse("new")}",
                  cls  := "text-md-primary hover:underline",
                  onClick --> { e =>
                    e.preventDefault()
                    Nav.toMaintainUser(u.id.getOrElse("new"))
                  },
                  "Edit"
                )
              )
            }
          )
        }
      )
    )
