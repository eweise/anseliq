package perfectpair.util

import tyrian.Cmd
import zio.*
import zio.interop.catz.*

import scala.concurrent.duration.*
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
object Flowbite:
  val initCmd: Cmd[Task, Nothing] =
    val effect = ZIO.attempt(FlowbiteJS.init()).delay(100.millis)
    Cmd.SideEffect(effect)

  @JSImport("js/flowbite.js", JSImport.Namespace)
  @js.native
  private object FlowbiteJS extends js.Object:
    def init(): Unit = js.native
