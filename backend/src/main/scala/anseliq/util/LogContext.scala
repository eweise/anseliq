package anseliq.util

import org.slf4j.MDC

object LogContext:
  def withContext[T](context: (String, Any)*)(block: => T): T =
    val originalValues = context.map { case (k, _) => k -> Option(MDC.get(k)) }

    try
      context.foreach { case (k, v) => MDC.put(k, v.toString) }
      block // Execute your application logging logic
    finally
      // Restore or clear context safely when the block finishes
      originalValues.foreach:
        case (k, Some(oldV)) => MDC.put(k, oldV)
        case (k, None)       => MDC.remove(k)
