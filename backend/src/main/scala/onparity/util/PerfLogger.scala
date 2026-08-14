package onparity.util

import org.slf4j.LoggerFactory

object PerfLogger {
  private val logger = LoggerFactory.getLogger("PerfLogger")

  def time[R](name: String)(block: => R): R = {
    val start  = System.nanoTime()
    val result = block
    val stop   = System.nanoTime()
    val millis = (stop - start) / 1_000_000.0
    logger.info(s"Action '$name' took $millis ms")
    result
  }
}
