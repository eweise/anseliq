package com.eweise.perfectpair.view.pages

import com.eweise.perfectpair.common.model.{FundFlowPayload, RealAccountSpecPayload}
import com.eweise.perfectpair.model.Msg
import com.eweise.perfectpair.view.components.{Buttons, Form}
import tyrian.Html.*
import tyrian.Html

import scala.:+

object MaintainFundFlowView:
  def apply(ff: FundFlowPayload): Html[Msg] =
    div(cls := "flex flex-col items-center mb-8 pt-20")(
      form(cls := "max-w-sm mx-auto")(
        Form.field(fieldID = "123", labelName = "Name", onChangFn = value => Msg.FundFlowFormUpdate(ff.copy(name = value)))
      ),
      Form.field(
        "123",
        "Aging Limit",
        value => Msg.FundFlowFormUpdate(ff.copy(name = value))
      ),
      Form.field(
        "account1",
        "First Data Source",
        value => Msg.FundFlowFormUpdate(ff.copy(realAccountSpecs = ff.realAccountSpecs :+ RealAccountSpecPayload(name = value)))
      ),
      Form.field(
        "account2",
        "Secon Data Source",
        value => Msg.FundFlowFormUpdate(ff.copy(realAccountSpecs = ff.realAccountSpecs :+ RealAccountSpecPayload(name = value)))
      ),
      Buttons.linkButton("Save", Msg.CreateFundFlow(ff))
    )

//<form class="max-w-sm mx-auto">
//  <div class="mb-5">
//    <label for="email" class="block mb-2.5 text-sm font-medium text-heading">Your email</label>
//    <input type="email" id="email" class="bg-neutral-secondary-medium border border-default-medium text-heading text-sm rounded-base focus:ring-brand focus:border-brand block w-full px-3 py-2.5 shadow-xs placeholder:text-body" placeholder="name@flowbite.com" required />
//  </div>
//  <div class="mb-5">
//    <label for="password" class="block mb-2.5 text-sm font-medium text-heading">Your password</label>
//    <input type="password" id="password" class="bg-neutral-secondary-medium border border-default-medium text-heading text-sm rounded-base focus:ring-brand focus:border-brand block w-full px-3 py-2.5 shadow-xs placeholder:text-body" placeholder="••••••••" required />
//  </div>
//  <label for="remember" class="flex items-center mb-5">
//    <input id="remember" type="checkbox" value="" class="w-4 h-4 border border-default-medium rounded-xs bg-neutral-secondary-medium focus:ring-2 focus:ring-brand-soft" required />
//    <p class="ms-2 text-sm font-medium text-heading select-none">I agree with the <a href="#" class="text-fg-brand hover:underline">terms and conditions</a>.</p>
//  </label>
//  <button type="submit" class="text-white bg-brand box-border border border-transparent hover:bg-brand-strong focus:ring-4 focus:ring-brand-medium shadow-xs font-medium leading-5 rounded-base text-sm px-4 py-2.5 focus:outline-none">Submit</button>
//</form>
